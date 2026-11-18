package eu.hansolo.duckinsurance.event;

import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.service.ClaimService;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

/** Reacts to new claims only after the surrounding transaction has committed. */
@ApplicationScoped
public class FraudCheck {

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
            }
        });
    }
}
