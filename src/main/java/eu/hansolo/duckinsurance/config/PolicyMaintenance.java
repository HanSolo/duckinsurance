package eu.hansolo.duckinsurance.config;

import eu.hansolo.duckinsurance.audit.AuditLog;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.enterprise.concurrent.Asynchronous;
import jakarta.enterprise.concurrent.Schedule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Jakarta Concurrency 3.1 @Schedule: runs every night at 02:00 without any timer boilerplate. */
@ApplicationScoped
public class PolicyMaintenance {

    @Inject
    private PolicyService policies;
    @Inject
    private AuditLog      log;


    @Asynchronous(runAt = @Schedule(cron = "0 2 * * *"))
    public void expireOverduePolicies() {
        log.record("scheduler", policies.expireOverdue() + " overdue policies expired");
    }
}
