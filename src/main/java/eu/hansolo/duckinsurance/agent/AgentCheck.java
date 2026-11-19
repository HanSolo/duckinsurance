package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.config.AppConfig;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.domain.PolicyType;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ManagedExecutorService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Starts the fraud agent for one claim, on request. The agent calls the clinic and the model, which takes a while, so it runs in the background. */
@ApplicationScoped
public class AgentCheck {
    private static final Logger LOG             = Logger.getLogger(AgentCheck.class.getName());
    private static final int    TIMEOUT_SECONDS = 120;

    @Resource(lookup = "java:app/concurrent/VirtualExecutor")
    private ManagedExecutorService   executor;
    @Inject
    private ClaimService             claims;
    @Inject
    private PolicyService            policies;
    @Inject
    private ClinicClient             clinic;
    @Inject
    private Event<ClaimCheckRequest> requests;


    public static boolean enabled() {
        return !"off".equalsIgnoreCase(AppConfig.get("DUCK_FRAUD_AGENT", "on"));
    }

    /**
     * @return true if the agent was started; false if the DuckClinic is not reachable and the claim got the rule-based check instead
     * @throws DomainException if the claim cannot be checked
     */
    public boolean start(long claimId) {
        if (!enabled()) throw DomainException.conflict("The fraud agent is switched off (DUCK_FRAUD_AGENT=off)");
        var claim = claims.get(claimId);
        if (!claim.getStatus().isOpen()) throw DomainException.conflict("Claim is already " + claim.getStatus());
        if (policies.get(claim.getPolicyId()).getType() != PolicyType.VET_CARE) throw DomainException.conflict("The clinic can only confirm vet-care claims");
        if (!clinic.check().state().equals("ok")) {
            claims.ruleCheck(claimId, "The DuckClinic is not reachable, so only the rules were applied.");
            return false;
        }
        claims.markChecking(claimId);
        LOG.info("Fraud agent: check of claim " + claimId + " requested");
        executor.submit(() -> {
            // The workflow runs in its own task, so that a model or clinic that never answers cannot block the claim forever
            Future<?> workflow = executor.submit(() -> requests.fire(new ClaimCheckRequest(claimId)));
            try {
                workflow.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                LOG.info("Fraud agent: workflow for claim " + claimId + " finished");
            } catch (TimeoutException e) {
                workflow.cancel(true);
                LOG.warning("Fraud agent: no result for claim " + claimId + " after " + TIMEOUT_SECONDS + " seconds");
                claims.agentFailed(claimId, "no answer after " + TIMEOUT_SECONDS + " seconds");
            } catch (ExecutionException e) {
                // the agent's own exception handler has already noted the failure on the claim, this makes sure of it
                LOG.log(Level.WARNING, "Fraud agent: workflow for claim " + claimId + " failed", e.getCause());
                claims.agentFailed(claimId, String.valueOf(e.getCause().getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.log(Level.SEVERE, "Fraud agent: could not finish claim " + claimId, e);
            }
        });
        return true;
    }
}
