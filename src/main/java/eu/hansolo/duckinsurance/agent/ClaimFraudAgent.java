package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.ai.agent.Action;
import jakarta.ai.agent.Agent;
import jakarta.ai.agent.Decision;
import jakarta.ai.agent.HandleException;
import jakarta.ai.agent.LargeLanguageModel;
import jakarta.ai.agent.Outcome;
import jakarta.ai.agent.Result;
import jakarta.ai.agent.Trigger;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.StringReader;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks vet-care claims against the DuckClinic, written to the draft Jakarta Agentic AI 1.0 (M1) model: a CDI event starts the workflow,
 * then trigger, action, decision and outcome run in this order, and the objects they return are passed on by type.
 * <ol>
 *   <li>{@link #start}: a claim check was requested.</li>
 *   <li>{@link #askClinic}: reads the duck's visits from the DuckClinic over MCP and keeps those near the incident.</li>
 *   <li>{@link #judge}: the language model compares the claim with the clinic's records and returns a score and a reason.</li>
 *   <li>{@link #record}: stores the verdict on the claim and moves it to review.</li>
 *   <li>{@link #fallBack}: if the clinic or the model fails, the claim gets the plain rule-based check instead.</li>
 * </ol>
 */
@Agent
public class ClaimFraudAgent {
    private static final Logger LOG          = Logger.getLogger(ClaimFraudAgent.class.getName());
    private static final int    NEARBY_DAYS  = 14;

    @Inject
    private LargeLanguageModel model;
    @Inject
    private ClinicClient       clinic;
    @Inject
    private ClaimService       claims;
    @Inject
    private PolicyService      policies;
    @Inject
    private DuckService        ducks;
    @Inject
    private CustomerService    customers;


    @Trigger
    void start(ClaimCheckRequest request) {
        LOG.fine(() -> "Fraud agent started for claim " + request.claimId());
    }

    @Action
    ClinicFacts askClinic(ClaimCheckRequest request) {
        Claim    claim    = claims.get(request.claimId());
        Policy   policy   = policies.get(claim.getPolicyId());
        Duck     duck     = ducks.get(policy.getDuckId());
        Customer owner    = customers.get(duck.getOwnerId());
        String   text     = "Claim %s: %s EUR for %s (policy %s, %s, coverage %s EUR), owner %s, incident on %s, description: \"%s\"".formatted(claim.getClaimNumber(), claim.getAmount(), duck.getName(), policy.getPolicyNumber(), policy.getType(), policy.getCoverageAmount(), owner.getFullName(), claim.getIncidentDate(), claim.getDescription());

        // Unreachable clinic: the exception goes to fallBack(), the claim is not judged on missing data
        var found = clinic.findDuck(owner.getLastName(), duck.getName());
        if (found.isEmpty()) return new ClinicFacts(claim.getId(), text, false, List.of(), List.of());
        List<String> visits = clinic.visits(found.get().id());
        List<String> nearby = visits.stream().filter(v -> Math.abs(ChronoUnit.DAYS.between(claim.getIncidentDate(), java.time.LocalDate.parse(v.substring(0, 10)))) <= NEARBY_DAYS).toList();
        return new ClinicFacts(claim.getId(), text, true, visits, nearby);
    }

    /** Always continues the workflow: the verdict travels in the details of the result, a high score is not a reason to stop. */
    @Decision
    Result judge(ClinicFacts facts) {
        String prompt = """
                You are a fraud analyst of a duck insurer. Compare the claim with the records of the veterinary clinic and judge whether the claim makes sense.
                Consider: does the clinic know the duck, is there a visit near the incident date, does the visit's description fit the claim, is the amount plausible for that treatment.
                A missing clinic visit for a vet-care claim is suspicious. A matching visit is a good sign.
                Answer only with JSON: {"score": <0 plausible .. 100 very suspicious>, "reason": "<one or two sentences>"}

                %s

                The clinic knows this duck: %s
                All clinic visits of the duck: %s
                Visits within %d days of the incident: %s
                """.formatted(facts.claimText(), facts.duckKnown(), facts.visits().isEmpty() ? "none" : String.join("; ", facts.visits()), NEARBY_DAYS, facts.nearbyVisits().isEmpty() ? "none" : String.join("; ", facts.nearbyVisits()));
        String answer = model.query(prompt);
        return new Result(true, parse(answer, facts));
    }

    @Outcome
    void record(ClaimCheckRequest request, Verdict verdict) {
        claims.applyVerdict(request.claimId(), verdict.score(), "[" + verdict.source() + "] " + verdict.reason());
    }

    /** Clinic or model failed: log it, give the claim the rule-based check, and end the workflow by rethrowing. */
    @HandleException
    void fallBack(RuntimeException e, ClaimCheckRequest request) {
        LOG.log(Level.WARNING, "Fraud agent failed for claim " + request.claimId() + ", using the rule-based check: " + e.getMessage());
        claims.runFraudCheck(request.claimId());
        throw e;
    }

    /** The model should answer with JSON; small models often add text around it, so take the first {...} and fall back to a rule if that fails. */
    static Verdict parse(String answer, ClinicFacts facts) {
        try {
            int from = answer.indexOf('{'), to = answer.lastIndexOf('}');
            JsonObject json = Json.createReader(new StringReader(answer.substring(from, to + 1))).readObject();
            int score = Math.max(0, Math.min(100, json.getInt("score")));
            return new Verdict(score, json.getString("reason", "No reason given"), "model");
        } catch (RuntimeException e) {
            boolean ok = facts.duckKnown() && !facts.nearbyVisits().isEmpty();
            return new Verdict(ok ? 15 : 70, ok ? "The clinic has a visit near the incident date (the model's answer could not be read)." : "No clinic visit near the incident date (the model's answer could not be read).", "rules");
        }
    }
}
