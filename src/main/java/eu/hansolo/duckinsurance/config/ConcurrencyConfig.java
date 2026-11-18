package eu.hansolo.duckinsurance.config;

import jakarta.enterprise.concurrent.ManagedExecutorDefinition;
import jakarta.enterprise.context.ApplicationScoped;

/** Jakarta Concurrency 3.1: a container-managed executor backed by virtual threads. */
@ManagedExecutorDefinition(name = "java:app/concurrent/VirtualExecutor", virtual = true)
@ApplicationScoped
public class ConcurrencyConfig {
}
