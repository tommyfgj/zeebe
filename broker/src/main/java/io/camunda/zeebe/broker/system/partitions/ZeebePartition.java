/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.broker.system.partitions;

import io.atomix.raft.RaftRoleChangeListener;
import io.atomix.raft.RaftServer.Role;
import io.atomix.raft.SnapshotReplicationListener;
import io.camunda.zeebe.broker.Loggers;
import io.camunda.zeebe.broker.exporter.stream.ExporterDirector;
import io.camunda.zeebe.broker.system.monitoring.DiskSpaceUsageListener;
import io.camunda.zeebe.broker.system.monitoring.HealthMetrics;
import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessor;
import io.camunda.zeebe.snapshots.PersistedSnapshotStore;
import io.camunda.zeebe.util.exception.UnrecoverableException;
import io.camunda.zeebe.util.health.CriticalComponentsHealthMonitor;
import io.camunda.zeebe.util.health.FailureListener;
import io.camunda.zeebe.util.health.HealthMonitorable;
import io.camunda.zeebe.util.health.HealthStatus;
import io.camunda.zeebe.util.sched.Actor;
import io.camunda.zeebe.util.sched.clock.ActorClock;
import io.camunda.zeebe.util.sched.future.ActorFuture;
import io.camunda.zeebe.util.sched.future.CompletableActorFuture;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;

public final class ZeebePartition extends Actor
    implements RaftRoleChangeListener,
        HealthMonitorable,
        FailureListener,
        DiskSpaceUsageListener,
        SnapshotReplicationListener {

  private static final Logger LOG = Loggers.SYSTEM_LOGGER;
  private Role raftRole;

  private final String actorName;
  private final List<FailureListener> failureListeners;
  private final HealthMetrics healthMetrics;
  private final RoleMetrics roleMetrics;
  private final ZeebePartitionHealth zeebePartitionHealth;

  private final PartitionContext context;
  private final PartitionTransition transition;
  private CompletableActorFuture<Void> closeFuture;
  private ActorFuture<Void> currentTransitionFuture;

  public ZeebePartition(
      final PartitionBoostrapAndTransitionContextImpl transitionContext,
      final PartitionTransition transition) {
    context = transitionContext.getPartitionContext();
    this.transition = transition;

    transitionContext.setActorControl(actor);
    transitionContext.setDiskSpaceAvailable(true);

    actorName =
        buildActorName(
            transitionContext.getNodeId(), "ZeebePartition", transitionContext.getPartitionId());
    transitionContext.setComponentHealthMonitor(new CriticalComponentsHealthMonitor(actor, LOG));
    zeebePartitionHealth = new ZeebePartitionHealth(transitionContext.getPartitionId());
    healthMetrics = new HealthMetrics(transitionContext.getPartitionId());
    healthMetrics.setUnhealthy();
    failureListeners = new ArrayList<>();
    roleMetrics = new RoleMetrics(transitionContext.getPartitionId());
  }

  /**
   * Called by atomix on role change.
   *
   * @param newRole the new role of the raft partition
   */
  @Override
  @Deprecated // will be removed from public API of ZeebePartition
  public void onNewRole(final Role newRole, final long newTerm) {
    actor.run(() -> onRoleChange(newRole, newTerm));
  }

  private void onRoleChange(final Role newRole, final long newTerm) {
    ActorFuture<Void> nextTransitionFuture = null;
    switch (newRole) {
      case LEADER:
        if (raftRole != Role.LEADER) {
          nextTransitionFuture = leaderTransition(newTerm);
        }
        break;
      case INACTIVE:
        nextTransitionFuture = transitionToInactive();
        break;
      case PASSIVE:
      case PROMOTABLE:
      case CANDIDATE:
      case FOLLOWER:
      default:
        if (raftRole == null || raftRole == Role.LEADER) {
          nextTransitionFuture = followerTransition(newTerm);
        }
        break;
    }

    if (nextTransitionFuture != null) {
      currentTransitionFuture = nextTransitionFuture;
    }
    LOG.debug("Partition role transitioning from {} to {} in term {}", raftRole, newRole, newTerm);
    raftRole = newRole;
  }

  private ActorFuture<Void> leaderTransition(final long newTerm) {
    final var installStartTime = ActorClock.currentTimeMillis();
    final var leaderTransitionFuture = transition.toLeader(newTerm);
    leaderTransitionFuture.onComplete(
        (success, error) -> {
          if (error == null) {
            final var leaderTransitionLatency = ActorClock.currentTimeMillis() - installStartTime;
            roleMetrics.setLeaderTransitionLatency(leaderTransitionLatency);
            final List<ActorFuture<Void>> listenerFutures =
                context.notifyListenersOfBecomingLeader(newTerm);
            actor.runOnCompletion(
                listenerFutures,
                t -> {
                  if (t != null) {
                    onInstallFailure(t);
                  }
                });
            onRecoveredInternal();
          } else {
            LOG.error("Failed to install leader partition {}", context.getPartitionId(), error);
            onInstallFailure(error);
          }
        });
    return leaderTransitionFuture;
  }

  private ActorFuture<Void> followerTransition(final long newTerm) {
    final var followerTransitionFuture = transition.toFollower(newTerm);
    followerTransitionFuture.onComplete(
        (success, error) -> {
          if (error == null) {
            final List<ActorFuture<Void>> listenerFutures =
                context.notifyListenersOfBecomingFollower(newTerm);
            actor.runOnCompletion(
                listenerFutures,
                t -> {
                  // Compare with the current term in case a new role transition happened
                  if (t != null) {
                    onInstallFailure(t);
                  }
                });
            onRecoveredInternal();
          } else {
            LOG.error("Failed to install follower partition {}", context.getPartitionId(), error);
            onInstallFailure(error);
          }
        });
    return followerTransitionFuture;
  }

  private ActorFuture<Void> transitionToInactive() {
    zeebePartitionHealth.setServicesInstalled(false);
    final var inactiveTransitionFuture = transition.toInactive();
    currentTransitionFuture = inactiveTransitionFuture;
    return inactiveTransitionFuture;
  }

  @Override
  public String getName() {
    return actorName;
  }

  @Override
  public void onActorStarting() {
    context.getRaftPartition().addRoleChangeListener(this);
    context.getComponentHealthMonitor().addFailureListener(this);
    onRoleChange(context.getRaftPartition().getRole(), context.getRaftPartition().term());
  }

  @Override
  protected void onActorStarted() {
    context.getComponentHealthMonitor().startMonitoring();
    context
        .getComponentHealthMonitor()
        .registerComponent(context.getRaftPartition().name(), context.getRaftPartition());
    // Add a component that keep track of health of ZeebePartition. This way
    // criticalComponentsHealthMonitor can monitor the health of ZeebePartition similar to other
    // components.
    context
        .getComponentHealthMonitor()
        .registerComponent(zeebePartitionHealth.getName(), zeebePartitionHealth);
  }

  @Override
  protected void onActorClosing() {
    transitionToInactive()
        .onComplete(
            (nothing, err) -> {
              context.getRaftPartition().removeRoleChangeListener(this);

              context
                  .getComponentHealthMonitor()
                  .removeComponent(context.getRaftPartition().name());
              closeFuture.complete(null);
            });
  }

  @Override
  protected void onActorCloseRequested() {
    LOG.debug("Closing ZeebePartition {}", context.getPartitionId());
    context.getComponentHealthMonitor().removeComponent(zeebePartitionHealth.getName());
  }

  @Override
  public ActorFuture<Void> closeAsync() {
    if (closeFuture != null) {
      return closeFuture;
    }

    closeFuture = new CompletableActorFuture<>();

    actor.run(
        () ->
            // allows to await current transition to avoid concurrent modifications and
            // transitioning
            currentTransitionFuture.onComplete((nothing, err) -> super.closeAsync()));

    return closeFuture;
  }

  @Override
  protected void handleFailure(final Exception failure) {
    LOG.warn("Uncaught exception in {}.", actorName, failure);
    // Most probably exception happened in the middle of installing leader or follower services
    // because this actor is not doing anything else
    onInstallFailure(failure);
  }

  @Override
  @Deprecated // will be removed from public API of ZeebePartition
  public void onFailure() {
    actor.run(
        () -> {
          healthMetrics.setUnhealthy();
          failureListeners.forEach(FailureListener::onFailure);
        });
  }

  @Override
  @Deprecated // will be removed from public API of ZeebePartition
  public void onRecovered() {
    actor.run(
        () -> {
          healthMetrics.setHealthy();
          failureListeners.forEach(FailureListener::onRecovered);
        });
  }

  @Override
  @Deprecated // will be removed from public API of ZeebePartition
  public void onUnrecoverableFailure() {
    actor.run(this::handleUnrecoverableFailure);
  }

  private void onInstallFailure(final Throwable error) {
    if (error instanceof UnrecoverableException) {
      LOG.error(
          "Failed to install partition {} (role {}, term {}) with unrecoverable failure: ",
          context.getPartitionId(),
          context.getCurrentRole(),
          context.getCurrentTerm(),
          error);
      handleUnrecoverableFailure();
    } else {
      handleRecoverableFailure();
    }
  }

  private void handleRecoverableFailure() {
    zeebePartitionHealth.setServicesInstalled(false);
    context.notifyListenersOfBecomingInactive();

    // If RaftPartition has already transition to a new role in a new term, we can ignore this
    // failure. The transition for the higher term will be already enqueued and services will be
    // installed for the new role.
    if (context.getCurrentRole() == Role.LEADER
        && context.getCurrentTerm() == context.getRaftPartition().term()) {
      LOG.info(
          "Unexpected failure occurred in partition {} (role {}, term {}), stepping down",
          context.getPartitionId(),
          context.getCurrentRole(),
          context.getCurrentTerm());
      context.getRaftPartition().stepDown();
    } else if (context.getCurrentRole() == Role.FOLLOWER) {
      LOG.info(
          "Unexpected failure occurred in partition {} (role {}, term {}), transitioning to inactive",
          context.getPartitionId(),
          context.getCurrentRole(),
          context.getCurrentTerm());
      context.getRaftPartition().goInactive();
    }
  }

  private void handleUnrecoverableFailure() {
    healthMetrics.setDead();
    zeebePartitionHealth.onUnrecoverableFailure();
    transitionToInactive();
    context.getRaftPartition().goInactive();
    failureListeners.forEach(FailureListener::onUnrecoverableFailure);
    context.notifyListenersOfBecomingInactive();
  }

  private void onRecoveredInternal() {
    zeebePartitionHealth.setServicesInstalled(true);
  }

  @Override
  public HealthStatus getHealthStatus() {
    return context.getComponentHealthMonitor().getHealthStatus();
  }

  @Override
  public void addFailureListener(final FailureListener failureListener) {
    actor.run(
        () -> {
          failureListeners.add(failureListener);
          if (getHealthStatus() == HealthStatus.HEALTHY) {
            failureListener.onRecovered();
          } else {
            failureListener.onFailure();
          }
        });
  }

  @Override
  public void removeFailureListener(final FailureListener failureListener) {
    actor.run(() -> failureListeners.remove(failureListener));
  }

  @Override
  @Deprecated // currently the implementation forwards this to other components inside the
  // partition; these components will be directly registered as listeners in the future
  public void onDiskSpaceNotAvailable() {
    actor.call(
        () -> {
          context.setDiskSpaceAvailable(false);
          zeebePartitionHealth.setDiskSpaceAvailable(false);
          if (context.getStreamProcessor() != null) {
            LOG.warn("Disk space usage is above threshold. Pausing stream processor.");
            context.getStreamProcessor().pauseProcessing();
          }
        });
  }

  @Override
  @Deprecated // currently the implementation forwards this to other components inside the
  // partition; these components will be directly registered as listeners in the future
  public void onDiskSpaceAvailable() {
    actor.call(
        () -> {
          context.setDiskSpaceAvailable(true);
          zeebePartitionHealth.setDiskSpaceAvailable(false);
          if (context.getStreamProcessor() != null && context.shouldProcess()) {
            LOG.info("Disk space usage is below threshold. Resuming stream processor.");
            context.getStreamProcessor().resumeProcessing();
          }
        });
  }

  @Deprecated // will be removed from public API of ZeebePartition
  public ActorFuture<Void> pauseProcessing() {
    final CompletableActorFuture<Void> completed = new CompletableActorFuture<>();
    actor.call(
        () -> {
          try {
            context.pauseProcessing();

            if (context.getStreamProcessor() != null && !context.shouldProcess()) {
              context.getStreamProcessor().pauseProcessing().onComplete(completed);
            } else {
              completed.complete(null);
            }
          } catch (final IOException e) {
            LOG.error("Could not pause processing state", e);
            completed.completeExceptionally(e);
          }
        });
    return completed;
  }

  @Deprecated // will be removed from public API of ZeebePartition
  public void resumeProcessing() {
    actor.call(
        () -> {
          try {
            context.resumeProcessing();
            if (context.getStreamProcessor() != null && context.shouldProcess()) {
              context.getStreamProcessor().resumeProcessing();
            }
          } catch (final IOException e) {
            LOG.error("Could not resume processing", e);
          }
        });
  }

  public int getPartitionId() {
    return context.getPartitionId();
  }

  public PersistedSnapshotStore getSnapshotStore() {
    return context.getRaftPartition().getServer().getPersistedSnapshotStore();
  }

  @Deprecated // will be removed from public API of ZeebePartition
  public void triggerSnapshot() {
    actor.call(
        () -> {
          context.triggerSnapshot();
        });
  }

  public ActorFuture<Optional<StreamProcessor>> getStreamProcessor() {
    return actor.call(() -> Optional.ofNullable(context.getStreamProcessor()));
  }

  public ActorFuture<Optional<ExporterDirector>> getExporterDirector() {
    return actor.call(() -> Optional.ofNullable(context.getExporterDirector()));
  }

  @Deprecated // will be removed from public API of ZeebePartition
  public ActorFuture<Void> pauseExporting() {
    final CompletableActorFuture<Void> completed = new CompletableActorFuture<>();
    actor.call(
        () -> {
          try {
            final var pauseStatePersisted = context.pauseExporting();

            if (context.getExporterDirector() != null && pauseStatePersisted) {
              context.getExporterDirector().pauseExporting().onComplete(completed);
            } else {
              completed.complete(null);
            }
          } catch (final IOException e) {
            LOG.error("Could not pause exporting", e);
            completed.completeExceptionally(e);
          }
        });
    return completed;
  }

  @Deprecated // will be removed from public API of ZeebePartition
  public void resumeExporting() {
    actor.call(
        () -> {
          try {
            context.resumeExporting();
            if (context.getExporterDirector() != null && context.shouldExport()) {
              context.getExporterDirector().resumeExporting();
            }
          } catch (final IOException e) {
            LOG.error("Could not resume exporting", e);
          }
        });
  }

  @Override
  public void onSnapshotReplicationStarted() {
    actor.run(() -> transition.toInactive());
  }

  @Override
  public void onSnapshotReplicationCompleted(final Role role, final long term) {
    actor.run(() -> onRoleChange(role, term));
  }
}
