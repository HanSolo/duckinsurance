package eu.hansolo.duckinsurance.event;

import eu.hansolo.duckinsurance.agent.ClaimCheckRequest;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.domain.PolicyType;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reacts to new claims only after the surrounding transaction has committed. Vet-care claims go to the fraud agent (DuckClinic check, see agent/ClaimFraudAgent)
 * unless DUCK_FRAUD_AGENT=off; all other claims, and any claim the agent cannot handle, get the plain rule-based check.
 */
@ApplicationScoped
public class FraudCheck {
    private static final Logger LOG = Logger.getLogger(FraudCheck.class.getName());

    @Resource(lookup = "java:app/concurrent/VirtualExecutor")
    private ManagedExecutorService   executor;
    @Inject
    private ClaimService             claims;
    @Inject
    private PolicyService            policies;
    @Inject
    private Event<ClaimCheckRequest> requests;


    void onClaimEvent(@Observes(during = TransactionPhase.AFTER_SUCCESS) ClaimEvent event) {
        if (event.to() != ClaimStatus.SUBMITTED) return;
        executor.submit(() -> {
            try {
                Thread.sleep(800);
                if (agentEnabled() && vetCare(event.claimId())) {
                    // The workflow of the agent starts with this CDI event; it stores the verdict itself and falls back to the rules on failure
                    requests.fire(new ClaimCheckRequest(event.claimId()));
                } else {
                    claims.runFraudCheck(event.claimId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Fraud agent workflow ended with " + e + " for claim " + event.claimId());
                claims.runFraudCheck(event.claimId());
            }
        });
    }

    private boolean vetCare(long claimId) {
        return policies.get(claims.get(claimId).getPolicyId()).getType() == PolicyType.VET_CARE;
    }

    static boolean agentEnabled() {
        return !"off".equalsIgnoreCase(System.getenv("DUCK_FRAUD_AGENT"));
    }
}
