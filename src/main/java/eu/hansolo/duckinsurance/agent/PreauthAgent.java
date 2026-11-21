package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.domain.PolicyStatus;
import eu.hansolo.duckinsurance.domain.PolicyType;
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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * The insurer's side of a referral: a clinic asks "will you pay for this treatment plan?". Same model as {@link ClaimFraudAgent}: a CDI event starts the workflow, then trigger, action, decision and outcome run in this order.
 * <ol>
 *   <li>{@link #start}: a request arrived.</li>
 *   <li>{@link #gather}: finds the duck and its physiotherapy policy and works out the limits: remaining coverage, sessions allowed, what is paid per session. Pure code.</li>
 *   <li>{@link #judge}: if a rule already decides (no policy, referral document missing, coverage used up) the code answers. Otherwise the language model, instructed with the policy file, decides how many sessions to approve inside the limits; the code clamps its answer.</li>
 *   <li>{@link #record}: stores the answer, reserves the coverage and tells the conversation log.</li>
 *   <li>{@link #fallBack}: if something fails the request is answered with an error, never with an approval.</li>
 * </ol>
 */
@Agent
public class PreauthAgent {
    private static final Logger LOG = Logger.getLogger(PreauthAgent.class.getName());

    @Inject
    private LargeLanguageModel model;
    @Inject
    private CustomerService    customers;
    @Inject
    private DuckService        ducks;
    @Inject
    private PolicyService      policies;
    @Inject
    private ClaimService       claims;
    @Inject
    private PreauthStore       store;
    @Inject
    private AgentLog           log;


    @Trigger
    void start(PreauthRequest request) {
        log.say(request.referralId(), null, "THOUGHT", "Request received from " + request.provider() + ": " + request.sessions() + " x " + request.treatmentName() + " for " + request.duckName() + " (" + request.ownerLastName() + "), " + request.pricePerSession() + " EUR per session. Referral document: " + (request.referralDocument() == null ? "not attached" : "attached") + ".");
    }

    @Action
    PreauthFacts gather(PreauthRequest request) {
        Optional<Duck> duck = findDuck(request.ownerLastName(), request.duckName());
        if (duck.isEmpty()) return blocked(request, "NO_POLICY", "DuckInsurance does not know a duck " + request.duckName() + " of owner " + request.ownerLastName() + ".", null, BigDecimal.ZERO, BigDecimal.ZERO);
        LocalDate first = request.firstSession() == null ? LocalDate.now() : request.firstSession();
        Optional<Policy> policy = policies.list().stream().filter(p -> p.getDuckId().equals(duck.get().getId()) && p.getType() == PolicyType.PHYSIO && p.getStatus() == PolicyStatus.ACTIVE && !p.getEndDate().isBefore(first)).findFirst();
        if (policy.isEmpty()) return blocked(request, "NO_POLICY", request.duckName() + " has no active physiotherapy policy on " + first + ". Vet-care policies do not pay for therapy.", null, BigDecimal.ZERO, BigDecimal.ZERO);

        Policy p = policy.get();
        BigDecimal consumed  = claims.list(null).stream().filter(c -> c.getPolicyId().equals(p.getId()) && c.getStatus() != ClaimStatus.REJECTED).map(Claim::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal reserved  = store.reserved(p.getId());
        BigDecimal remaining = p.getCoverageAmount().subtract(consumed).subtract(reserved).max(BigDecimal.ZERO);
        BigDecimal perSession = request.pricePerSession().min(PreauthPolicy.MAX_PER_SESSION);
        int allowed = Math.min(Math.min(request.sessions(), PreauthPolicy.MAX_SESSIONS_PER_REFERRAL), remaining.divide(perSession, 0, RoundingMode.DOWN).intValue());

        long policyAge = ChronoUnit.DAYS.between(p.getStartDate(), first);
        String blocker = null;
        String message = null;
        if (policyAge < PreauthPolicy.WAITING_PERIOD_DAYS) {
            blocker = "WAITING_PERIOD";
            message = "The policy " + p.getPolicyNumber() + " is younger than " + PreauthPolicy.WAITING_PERIOD_DAYS + " days (waiting period).";
        } else if (first.isAfter(LocalDate.now().plusDays(PreauthPolicy.START_WITHIN_DAYS))) {
            blocker = "TOO_LATE";
            message = "The first session is more than " + PreauthPolicy.START_WITHIN_DAYS + " days away. Please ask again closer to the date.";
        } else if (request.referralDocument() == null || request.referralDocument().isBlank()) {
            blocker = "NEEDS_DOCUMENT";
            message = "Policy rule: a veterinary referral document is required before DuckInsurance decides.";
        } else if (allowed < 1) {
            blocker = "EXHAUSTED";
            message = "The remaining coverage of policy " + p.getPolicyNumber() + " (" + remaining + " EUR) does not pay for one session.";
        }
        log.say(request.referralId(), null, "POLICY", "Policy " + p.getPolicyNumber() + " (PHYSIO): coverage " + p.getCoverageAmount() + " EUR, claims " + consumed + " EUR, reserved " + reserved + " EUR, remaining " + remaining + " EUR. Limits: at most " + PreauthPolicy.MAX_SESSIONS_PER_REFERRAL + " sessions and " + PreauthPolicy.MAX_PER_SESSION + " EUR per session. Rules allow " + Math.max(allowed, 0) + " session(s) at " + perSession + " EUR." + (blocker == null ? "" : " Blocked by rule " + blocker + "."));
        return new PreauthFacts(request, blocker, message, p.getId(), p.getPolicyNumber(), p.getCoverageAmount(), consumed, reserved, remaining, perSession, Math.max(allowed, 0));
    }

    /** Always continues the workflow: the answer travels in the details of the result. */
    @Decision
    Result judge(PreauthFacts facts) {
        PreauthRequest request = facts.request();
        if (facts.blocker() != null) {
            return new Result(true, facts.blocker().equals("NEEDS_DOCUMENT") ? needsInfo(facts) : denied(facts, facts.message()));
        }
        String prompt = """
                %s

                A clinic asks for a pre-authorization. The code already checked that the policy is active, the waiting period is over and the referral document is attached. The limits below were calculated by the code and are final: do not check these again and never deny because of them.

                Request: %s asks for %d sessions of "%s" for the duck %s, price %s EUR per session.
                Diagnosis according to the clinic: %s
                Veterinary referral document:
                %s

                Limits: at most %d session(s) can be approved, the insurer pays %s EUR per session.

                Decide how many sessions to approve (0 to %d). Answer only with JSON: {"approvedSessions": <number>, "reason": "<one or two sentences>"}
                """.formatted(PreauthPolicy.text(), request.provider(), request.sessions(), request.treatmentName(), request.duckName(), request.pricePerSession(), request.diagnosis(), request.referralDocument(), facts.maxSessions(), facts.perSession(), facts.maxSessions());
        log.say(request.referralId(), null, "THOUGHT", "Asking the language model whether the plan fits the referral (it may approve between 0 and " + facts.maxSessions() + " sessions).");
        String text = model.query(prompt);
        if (text == null || text.isBlank()) throw new IllegalStateException("The model returned no answer");
        return new Result(true, parse(text, facts));
    }

    @Outcome
    void record(PreauthRequest request, PreauthAnswer answer) {
        store.putAnswer(request.correlation(), answer);
        PreauthStore.Preauth saved = store.save(request, answer);
        String verdict = switch (answer.status()) {
            case "APPROVED"   -> "APPROVED " + answer.approvedSessions() + " session(s) at " + answer.approvedPerSession() + " EUR (" + answer.approvedTotal() + " EUR). Reference " + saved.reference() + ". " + answer.reason();
            case "PARTIAL"    -> (answer.approvedSessions() == request.sessions() ? "APPROVED all " + answer.approvedSessions() + " session(s), the price is capped at " + answer.approvedPerSession() + " EUR per session (" + answer.approvedTotal() + " EUR). " : "PARTIALLY approved: " + answer.approvedSessions() + " of " + request.sessions() + " session(s), " + answer.approvedPerSession() + " EUR per session (" + answer.approvedTotal() + " EUR). ") + "Reference " + saved.reference() + ". " + answer.reason();
            case "NEEDS_INFO" -> "I cannot decide yet. Missing: " + String.join(", ", answer.missing()) + ". " + answer.reason();
            default           -> "DENIED. " + answer.reason();
        };
        log.say(request.referralId(), request.provider(), "ANSWER", verdict + (answer.source().equals("rules") ? " [decided by the rules]" : ""));
    }

    @HandleException
    void fallBack(RuntimeException e, PreauthRequest request) {
        LOG.log(Level.WARNING, "Pre-authorization agent failed for " + request.referralId(), e);
        log.say(request.referralId(), request.provider(), "ERROR", "The pre-authorization failed (" + e.getMessage() + "). Nothing was approved.");
        store.putAnswer(request.correlation(), new PreauthAnswer("ERROR", 0, BigDecimal.ZERO, BigDecimal.ZERO, "The insurer's agent failed: " + e.getMessage(), List.of(), 0, "", "rules"));
        throw e;
    }

    /** The model should answer with JSON; the answer is clamped to the limits of the code. If it cannot be read, the code's maximum is used (and the source says so). */
    static PreauthAnswer parse(String text, PreauthFacts facts) {
        int sessions = facts.maxSessions();
        String reason = "The plan fits the policy rules (the model's answer could not be read).";
        String source = "rules";
        try {
            int from = text.indexOf('{'), to = text.lastIndexOf('}');
            JsonObject json = Json.createReader(new StringReader(text.substring(from, to + 1))).readObject();
            sessions = Math.max(0, Math.min(facts.maxSessions(), json.getInt("approvedSessions")));
            reason   = json.getString("reason", "No reason given");
            source   = "agentic-ai";
        } catch (RuntimeException e) {
            // keep the defaults
        }
        if (sessions == 0) return new PreauthAnswer("DENIED", 0, BigDecimal.ZERO, BigDecimal.ZERO, reason, List.of(), facts.policyId(), facts.policyNumber(), source);
        BigDecimal total = facts.perSession().multiply(BigDecimal.valueOf(sessions));
        boolean full = sessions == facts.request().sessions() && facts.perSession().compareTo(facts.request().pricePerSession()) == 0;
        String note = facts.perSession().compareTo(facts.request().pricePerSession()) < 0 ? " The insurer pays at most " + PreauthPolicy.MAX_PER_SESSION + " EUR per session, the owner pays the difference." : "";
        return new PreauthAnswer(full ? "APPROVED" : "PARTIAL", sessions, facts.perSession(), total, reason + note, List.of(), facts.policyId(), facts.policyNumber(), source);
    }

    private static PreauthAnswer needsInfo(PreauthFacts facts) {
        return new PreauthAnswer("NEEDS_INFO", 0, BigDecimal.ZERO, BigDecimal.ZERO, facts.message(), List.of("referralDocument"), facts.policyId(), facts.policyNumber(), "rules");
    }

    private static PreauthAnswer denied(PreauthFacts facts, String reason) {
        return new PreauthAnswer("DENIED", 0, BigDecimal.ZERO, BigDecimal.ZERO, reason, List.of(), facts.policyId(), facts.policyNumber() == null ? "" : facts.policyNumber(), "rules");
    }

    private PreauthFacts blocked(PreauthRequest request, String blocker, String message, String policyNumber, BigDecimal coverage, BigDecimal zero) {
        log.say(request.referralId(), null, "POLICY", message);
        return new PreauthFacts(request, blocker, message, 0, policyNumber, coverage, zero, zero, zero, request.pricePerSession().min(PreauthPolicy.MAX_PER_SESSION), 0);
    }

    private Optional<Duck> findDuck(String ownerLastName, String duckName) {
        for (Customer owner : customers.list(ownerLastName)) {
            if (!owner.getLastName().equalsIgnoreCase(ownerLastName)) continue;
            for (Duck duck : ducks.list(owner.getId())) if (duck.getName().equalsIgnoreCase(duckName)) return Optional.of(duck);
        }
        return Optional.empty();
    }
}
