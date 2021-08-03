/*
 * Copyright 2016-present Open Networking Foundation
 * Copyright © 2020 camunda services GmbH (info@camunda.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.raft.partition.impl;

import io.atomix.cluster.ClusterMembershipService;
import io.atomix.cluster.MemberId;
import io.atomix.cluster.messaging.ClusterCommunicationService;
import io.atomix.primitive.partition.Partition;
import io.atomix.primitive.partition.PartitionMetadata;
import io.atomix.raft.RaftCommitListener;
import io.atomix.raft.RaftCommittedEntryListener;
import io.atomix.raft.RaftRoleChangeListener;
import io.atomix.raft.RaftServer;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.SnapshotReplicationListener;
import io.atomix.raft.metrics.RaftStartupMetrics;
import io.atomix.raft.partition.RaftElectionConfig;
import io.atomix.raft.partition.RaftPartition;
import io.atomix.raft.partition.RaftPartitionGroupConfig;
import io.atomix.raft.partition.RaftStorageConfig;
import io.atomix.raft.roles.RaftRole;
import io.atomix.raft.storage.RaftStorage;
import io.atomix.raft.storage.StorageException;
import io.atomix.raft.storage.log.RaftLogReader;
import io.atomix.raft.zeebe.ZeebeLogAppender;
import io.atomix.utils.Managed;
import io.atomix.utils.concurrent.Futures;
import io.atomix.utils.logging.ContextualLoggerFactory;
import io.atomix.utils.logging.LoggerContext;
import io.atomix.utils.serializer.Serializer;
import io.camunda.zeebe.snapshots.PersistedSnapshotStore;
import io.camunda.zeebe.snapshots.ReceivableSnapshotStore;
import io.camunda.zeebe.util.health.FailureListener;
import io.camunda.zeebe.util.health.HealthMonitorable;
import io.camunda.zeebe.util.health.HealthStatus;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import org.slf4j.Logger;

/** {@link Partition} server. */
public class RaftPartitionServer implements Managed<RaftPartitionServer>, HealthMonitorable {

  private final Logger log;

  private final MemberId localMemberId;
  private final RaftPartition partition;
  private final RaftPartitionGroupConfig config;
  private final ClusterMembershipService membershipService;
  private final ClusterCommunicationService clusterCommunicator;
  private final Set<RaftRoleChangeListener> deferredRoleChangeListeners =
      new CopyOnWriteArraySet<>();
  private final Set<FailureListener> deferredFailureListeners = new CopyOnWriteArraySet<>();
  private final PartitionMetadata partitionMetadata;
  private final Duration requestTimeout;

  private RaftServer server;
  private ReceivableSnapshotStore persistedSnapshotStore;

  public RaftPartitionServer(
      final RaftPartition partition,
      final RaftPartitionGroupConfig config,
      final MemberId localMemberId,
      final ClusterMembershipService membershipService,
      final ClusterCommunicationService clusterCommunicator,
      final PartitionMetadata partitionMetadata) {
    this.partition = partition;
    this.config = config;
    this.localMemberId = localMemberId;
    this.membershipService = membershipService;
    this.clusterCommunicator = clusterCommunicator;
    log =
        ContextualLoggerFactory.getLogger(
            getClass(),
            LoggerContext.builder(RaftPartitionServer.class).addValue(partition.name()).build());
    this.partitionMetadata = partitionMetadata;
    requestTimeout = config.getPartitionConfig().getRequestTimeout();
  }

  @Override
  public CompletableFuture<RaftPartitionServer> start() {
    final RaftStartupMetrics raftStartupMetrics = new RaftStartupMetrics(partition.name());
    final long bootstrapStartTime;
    log.info("Starting server for partition {}", partition.id());
    final long startTime = System.currentTimeMillis();
    final CompletableFuture<RaftServer> serverOpenFuture;
    if (partition.members().contains(localMemberId)) {
      if (server != null && server.isRunning()) {
        return CompletableFuture.completedFuture(null);
      }
      synchronized (this) {
        try {
          initServer();

        } catch (final StorageException e) {
          return Futures.exceptionalFuture(e);
        }
      }
      bootstrapStartTime = System.currentTimeMillis();
      serverOpenFuture = server.bootstrap(partition.members());
    } else {
      bootstrapStartTime = System.currentTimeMillis();
      serverOpenFuture = CompletableFuture.completedFuture(null);
    }
    return serverOpenFuture
        .whenComplete(
            (r, e) -> {
              if (e == null) {
                final long endTime = System.currentTimeMillis();
                final long startDuration = endTime - startTime;
                raftStartupMetrics.observeBootstrapDuration(endTime - bootstrapStartTime);
                raftStartupMetrics.observeStartupDuration(startDuration);
                log.info(
                    "Successfully started server for partition {} in {}ms",
                    partition.id(),
                    startDuration);
              } else {
                log.warn("Failed to start server for partition {}", partition.id(), e);
              }
            })
        .thenApply(v -> this);
  }

  @Override
  public boolean isRunning() {
    return server.isRunning();
  }

  @Override
  public CompletableFuture<Void> stop() {
    return server.shutdown();
  }

  private void initServer() {
    server = buildServer();

    deferredRoleChangeListeners.forEach(server::addRoleChangeListener);
    deferredRoleChangeListeners.clear();

    deferredFailureListeners.forEach(server::addFailureListener);
    deferredFailureListeners.clear();
  }

  private RaftServer buildServer() {
    final var partitionId = partition.id().id();
    persistedSnapshotStore =
        config
            .getStorageConfig()
            .getPersistedSnapshotStoreFactory()
            .createReceivableSnapshotStore(partition.dataDirectory().toPath(), partitionId);

    final var partitionConfig = config.getPartitionConfig();

    final var electionConfig =
        partitionConfig.isPriorityElectionEnabled()
            ? RaftElectionConfig.ofPriorityElection(
                partitionMetadata.getTargetPriority(), partitionMetadata.getPriority(localMemberId))
            : RaftElectionConfig.ofDefaultElection();

    return RaftServer.builder(localMemberId)
        .withName(partition.name())
        .withMembershipService(membershipService)
        .withProtocol(createServerProtocol())
        .withPartitionConfig(partitionConfig)
        .withStorage(createRaftStorage())
        .withEntryValidator(config.getEntryValidator())
        .withElectionConfig(electionConfig)
        .build();
  }

  public CompletableFuture<Void> goInactive() {
    return server.goInactive();
  }

  /**
   * Takes a snapshot of the partition server.
   *
   * @return a future to be completed once the snapshot has been taken
   */
  public CompletableFuture<Void> snapshot() {
    return server.compact();
  }

  public void setCompactableIndex(final long index) {
    server.getContext().getLogCompactor().setCompactableIndex(index);
  }

  public RaftLogReader openReader() {
    return server.getContext().getLog().openCommittedReader();
  }

  public void addRoleChangeListener(final RaftRoleChangeListener listener) {
    if (server == null) {
      deferredRoleChangeListeners.add(listener);
    } else {
      server.addRoleChangeListener(listener);
    }
  }

  @Override
  public HealthStatus getHealthStatus() {
    return server.getContext().getHealthStatus();
  }

  @Override
  public void addFailureListener(final FailureListener listener) {
    if (server == null) {
      deferredFailureListeners.add(listener);
    } else {
      server.addFailureListener(listener);
    }
  }

  public void removeFailureListener(final FailureListener listener) {
    deferredFailureListeners.remove(listener);
    server.removeFailureListener(listener);
  }

  public void removeRoleChangeListener(final RaftRoleChangeListener listener) {
    deferredRoleChangeListeners.remove(listener);
    server.removeRoleChangeListener(listener);
  }

  /** @see io.atomix.raft.impl.RaftContext#addCommitListener(RaftCommitListener) */
  public void addCommitListener(final RaftCommitListener commitListener) {
    server.getContext().addCommitListener(commitListener);
  }

  /** @see io.atomix.raft.impl.RaftContext#removeCommitListener(RaftCommitListener) */
  public void removeCommitListener(final RaftCommitListener commitListener) {
    server.getContext().removeCommitListener(commitListener);
  }

  /** @see io.atomix.raft.impl.RaftContext#addCommittedEntryListener(RaftCommittedEntryListener) */
  public void addCommittedEntryListener(final RaftCommittedEntryListener commitListener) {
    server.getContext().addCommittedEntryListener(commitListener);
  }

  /**
   * @see
   *     io.atomix.raft.impl.RaftContext#addSnapshotReplicationListener(SnapshotReplicationListener)
   */
  public void addSnapshotReplicationListener(final SnapshotReplicationListener logResetListener) {
    server.getContext().addSnapshotReplicationListener(logResetListener);
  }

  /**
   * @see
   *     io.atomix.raft.impl.RaftContext#removeSnapshotReplicationListener(SnapshotReplicationListener)
   */
  public void removeSnapshotReplicationListener(
      final SnapshotReplicationListener logResetListener) {
    server.getContext().removeSnapshotReplicationListener(logResetListener);
  }

  public PersistedSnapshotStore getPersistedSnapshotStore() {
    return persistedSnapshotStore;
  }

  /** Deletes the server. */
  public void delete() {
    try {
      Files.walkFileTree(
          partition.dataDirectory().toPath(),
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs)
                throws IOException {
              Files.delete(file);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc)
                throws IOException {
              Files.delete(dir);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (final IOException e) {
      log.error("Failed to delete partition: {}", partition, e);
    }
  }

  public Optional<ZeebeLogAppender> getAppender() {
    final RaftRole role = server.getContext().getRaftRole();
    if (role instanceof ZeebeLogAppender) {
      return Optional.of((ZeebeLogAppender) role);
    }

    return Optional.empty();
  }

  public Role getRole() {
    return server.getRole();
  }

  public long getTerm() {
    return server.getTerm();
  }

  private RaftStorage createRaftStorage() {
    final RaftStorageConfig storageConfig = config.getStorageConfig();
    return RaftStorage.builder()
        .withPrefix(partition.name())
        .withDirectory(partition.dataDirectory())
        .withMaxSegmentSize((int) storageConfig.getSegmentSize().bytes())
        .withFlushExplicitly(storageConfig.shouldFlushExplicitly())
        .withFreeDiskSpace(storageConfig.getFreeDiskSpace())
        .withSnapshotStore(persistedSnapshotStore)
        .withJournalIndexDensity(storageConfig.getJournalIndexDensity())
        .build();
  }

  private RaftServerCommunicator createServerProtocol() {
    return new RaftServerCommunicator(
        partition.name(),
        Serializer.using(RaftNamespaces.RAFT_PROTOCOL),
        clusterCommunicator,
        requestTimeout);
  }

  public CompletableFuture<Void> stepDown() {
    return server.stepDown();
  }
}
