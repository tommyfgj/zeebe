/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.transport;

import io.atomix.cluster.messaging.MessagingService;
import io.camunda.zeebe.transport.impl.AtomixClientTransportAdapter;
import io.camunda.zeebe.transport.impl.AtomixServerTransport;
import io.camunda.zeebe.util.sched.ActorSchedulerImpl;

public final class TransportFactory {

  // we need to schedule the transports, but Actor is not an interface
  // which means we need to schedule in the factory otherwise we can return the transport interface
  // types
  private final ActorSchedulerImpl actorScheduler;

  public TransportFactory(final ActorSchedulerImpl actorScheduler) {
    this.actorScheduler = actorScheduler;
  }

  public ServerTransport createServerTransport(
      final int nodeId, final MessagingService messagingService) {
    final var atomixServerTransport = new AtomixServerTransport(nodeId, messagingService);
    actorScheduler.submitActor(atomixServerTransport);
    return atomixServerTransport;
  }

  public ClientTransport createClientTransport(final MessagingService messagingService) {
    final var atomixClientTransportAdapter = new AtomixClientTransportAdapter(messagingService);
    actorScheduler.submitActor(atomixClientTransportAdapter);
    return atomixClientTransportAdapter;
  }
}
