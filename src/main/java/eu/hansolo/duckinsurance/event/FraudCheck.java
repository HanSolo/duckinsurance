package eu.hansolo.duckinsurance.event;

import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.service.ClaimService;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reacts to new claims only after the surrounding transaction has committed and gives them the quick rule-based fraud check.
 * The slower check against the DuckClinic by the fraud agent is started per claim with the Check button (see agent/AgentCheck).
 */
@ApplicationScoped
public class FraudCheck {
    private static final Logger LOG = Logger.getLogger(FraudCheck.class.getName());

    @Resource(lookup = "java:app/concurrent/VirtualExecutor")
    private ManagedExecutorService executor;
    @Inject
    private ClaimService           claims;


    void onClaimEvent(@Observes(during = TransactionPhase.AFTER_SUCCESS) ClaimEvent event) {
        if (event.to() != ClaimStatus.SUBMITTED) return;
        executor.submit(() -> {
            try {
                Thread.sleep(800);
                claims.runFraudCheck(event.claimId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                // an exception in a submitted task would otherwise disappear without a trace
                LOG.log(Level.SEVERE, "Rule-based fraud check failed for claim " + event.claimId(), e);
            }
        });
    }
}
