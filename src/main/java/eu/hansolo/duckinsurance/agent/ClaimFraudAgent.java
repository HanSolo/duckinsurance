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
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Checks vet-care and physiotherapy claims against the DuckClinic and the Duck Physical Therapy Clinic, written to the draft Jakarta Agentic AI 1.0 (M1) model: a CDI event starts the workflow,
 * then trigger, action, decision and outcome run in this order, and the objects they return are passed on by type.
 * <ol>
 *   <li>{@link #start}: a claim check was requested.</li>
 *   <li>{@link #askClinic}: reads the duck's visits from the DuckClinic and its appointments from the physical therapy clinic over MCP and keeps those near the incident.</li>
 *   <li>{@link #judge}: the language model compares the claim with the records and returns a score and a reason. If neither clinic has the treatment, the agent proposes a booking in the physical therapy clinic.</li>
 *   <li>{@link #record}: stores the verdict and the proposal on the claim and moves it to review.</li>
 *   <li>{@link #fallBack}: if no clinic can be reached the claim gets the rule-based check; if the model fails it keeps its rule-based score.</li>
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
    private PhysioClient       physio;
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
        LOG.info("Fraud agent: workflow started for claim " + request.claimId());
    }

    @Action
    ClinicFacts askClinic(ClaimCheckRequest request) {
        Claim    claim    = claims.get(request.claimId());
        Policy   policy   = policies.get(claim.getPolicyId());
        Duck     duck     = ducks.get(policy.getDuckId());
        Customer owner    = customers.get(duck.getOwnerId());
        String   text     = "Claim %s: %s EUR for %s (policy %s, %s, coverage %s EUR), owner %s, incident on %s, description: \"%s\"".formatted(claim.getClaimNumber(), claim.getAmount(), duck.getName(), policy.getPolicyNumber(), policy.getType(), policy.getCoverageAmount(), owner.getFullName(), claim.getIncidentDate(), claim.getDescription());
        LocalDate incident = claim.getIncidentDate();

        // A clinic that cannot be reached is unknown, not suspicious. Only if neither answers, the exception goes to fallBack() and the claim gets the rule-based check.
        boolean vetUp = true, vetKnown = false;
        List<String> vetVisits = List.of(), vetNearby = List.of();
        LOG.info("Fraud agent: asking the DuckClinic about claim " + request.claimId());
        try {
            var found = clinic.findDuck(owner.getLastName(), duck.getName());
            vetKnown = found.isPresent();
            if (vetKnown) {
                vetVisits = clinic.visits(found.get().id());
                vetNearby = vetVisits.stream().filter(v -> near(incident, v.substring(0, 10))).toList();
            }
        } catch (ClinicUnavailableException e) {
            vetUp = false;
            LOG.info("Fraud agent: the DuckClinic is not reachable");
        }
        boolean physioUp = true;
        long physioDuckId = 0;
        List<String> physioAppts = List.of(), physioNearby = List.of();
        List<PhysioClient.Option> treatments = List.of(), therapists = List.of();
        LOG.info("Fraud agent: asking the Duck Physical Therapy Clinic about claim " + request.claimId());
        try {
            var found = physio.findDuck(owner.getLastName(), duck.getName());
            if (found.isPresent()) {
                physioDuckId = found.get().id();
                var appointments = physio.appointments(physioDuckId);
                physioAppts = appointments.stream().map(PhysioClient.Appointment::text).toList();
                physioNearby = appointments.stream().filter(a -> near(incident, a.date().toString())).map(PhysioClient.Appointment::text).toList();
                treatments = physio.treatments();
                therapists = physio.therapists();
            }
        } catch (ClinicUnavailableException e) {
            physioUp = false;
            LOG.info("Fraud agent: the Duck Physical Therapy Clinic is not reachable");
        }
        if (!vetUp && !physioUp) throw new ClinicUnavailableException("Neither clinic is reachable", null);
        return new ClinicFacts(claim.getId(), text, policy.getType().name(), claim.getDescription(), duck.getId(), vetUp, vetKnown, vetVisits, vetNearby, physioUp, physioDuckId, physioAppts, physioNearby, treatments, therapists);
    }

    /** Always continues the workflow: the verdict travels in the details of the result, a high score is not a reason to stop. */
    @Decision
    Result judge(ClinicFacts facts) {
        String prompt = """
                You are a fraud analyst of a duck insurer. Compare the claim with the records of two clinics and judge whether the claim makes sense:
                the DuckClinic (veterinary visits) and the Duck Physical Therapy Clinic (therapy appointments such as hydrotherapy, treadmill, wing mobilisation).
                Consider: does a clinic know the duck, is there a visit or appointment near the incident date, does its description fit the claim, is the amount plausible for that treatment.
                Surgery and medical treatment belong to the veterinary clinic, therapy and rehabilitation to the physical therapy clinic. A missing record in the clinic that fits the claim is suspicious, a matching record is a good sign.
                A clinic that could not be reached tells nothing; do not hold it against the claim.
                Answer only with JSON: {"score": <0 plausible .. 100 very suspicious>, "reason": "<one or two sentences>", "treatmentId": <id from the list below that fits the claim best, or null>, "therapistId": <id or null>}

                %s

                DuckClinic: %s
                Veterinary visits of the duck: %s
                Visits within %d days of the incident: %s

                Physical therapy clinic: %s
                Appointments of the duck: %s
                Appointments within %d days of the incident: %s
                Treatments (id: name): %s
                Therapists (id: name): %s
                """.formatted(facts.claimText(),
                              !facts.vetReachable() ? "not reachable" : facts.vetDuckKnown() ? "knows this duck" : "does not know this duck",
                              list(facts.vetVisits()), NEARBY_DAYS, list(facts.vetNearby()),
                              !facts.physioReachable() ? "not reachable" : facts.physioDuckId() > 0 ? "knows this duck" : "does not know this duck",
                              list(facts.physioAppts()), NEARBY_DAYS, list(facts.physioNearby()),
                              list(facts.treatments().stream().map(o -> o.id() + ": " + o.text()).toList()), list(facts.therapists().stream().map(o -> o.id() + ": " + o.text()).toList()));
        LOG.info("Fraud agent: asking the model about claim " + facts.claimId());
        Answer answer = ask(prompt);
        return new Result(true, parse(answer.text(), facts, answer.via()));
    }

    /** The standard LargeLanguageModel of the runtime, configured with payara.agentic.llm.* (see compose.yaml). Failures end the workflow in fallBack(). */
    private Answer ask(String prompt) {
        String answer = model.query(prompt);
        if (answer == null || answer.isBlank()) throw new IllegalStateException("The model returned no answer");
        return new Answer(answer, "agentic-ai");
    }

    private record Answer(String text, String via) {
    }

    @Outcome
    void record(ClaimCheckRequest request, Verdict verdict) {
        claims.applyVerdict(request.claimId(), verdict.score(), "[" + verdict.source() + "] " + verdict.reason(), verdict.proposal());
    }

    /**
     * The workflow cannot go on. No clinic: the claim gets the rule-based check, which is no failure worth a stack trace. Anything else (model, bug): note it on the claim.
     * Rethrowing ends the workflow.
     */
    @HandleException
    void fallBack(RuntimeException e, ClaimCheckRequest request) {
        if (e instanceof ClinicUnavailableException) {
            LOG.info("Fraud agent: no clinic is reachable, claim " + request.claimId() + " gets the rule-based check");
            claims.ruleCheck(request.claimId(), "The clinics are not reachable, so only the rules were applied.");
        } else {
            LOG.log(Level.WARNING, "Fraud agent failed for claim " + request.claimId() + ", keeping the rule-based score: " + e);
            claims.agentFailed(request.claimId(), String.valueOf(e.getMessage()));
        }
        throw e;
    }

    /** The model should answer with JSON; small models often add text around it, so take the first {...} and fall back to a rule if that fails. */
    static Verdict parse(String answer, ClinicFacts facts, String via) {
        int score;
        String reason;
        JsonObject json = null;
        try {
            int from = answer.indexOf('{'), to = answer.lastIndexOf('}');
            json   = Json.createReader(new StringReader(answer.substring(from, to + 1))).readObject();
            score  = Math.max(0, Math.min(100, json.getInt("score")));
            reason = json.getString("reason", "No reason given");
        } catch (RuntimeException e) {
            score  = facts.found() ? 15 : 70;
            reason = (facts.found() ? "A clinic has a record near the incident date" : "No clinic has a record near the incident date") + " (the model's answer could not be read).";
            via    = "rules";
        }
        return new Verdict(score, reason, via, proposal(facts, json));
    }

    /**
     * No record of the treatment in either clinic, the duck is a patient of the physical therapy clinic, and the claim is about therapy: propose a booking there.
     * The model may name the treatment and the therapist; otherwise the first of each is used. The date is the next working day at 10:00 (the clinic checks availability when it is booked).
     */
    static String proposal(ClinicFacts facts, JsonObject json) {
        boolean therapy = facts.policyType().equals("PHYSIO") || facts.description().toLowerCase().matches(".*(physio|therapy|therapeut|hydro|rehab|treadmill|mobilis|massage|laser).*");
        if (!therapy || facts.found() || !facts.physioReachable() || facts.physioDuckId() == 0 || facts.treatments().isEmpty() || facts.therapists().isEmpty()) return null;
        PhysioClient.Option treatment = pick(facts.treatments(), json, "treatmentId");
        PhysioClient.Option therapist = pick(facts.therapists(), json, "therapistId");
        LocalDate day = LocalDate.now().plusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) day = day.plusDays(1);
        LocalDateTime start = day.atTime(LocalTime.of(10, 0));
        String text = "Not found in either clinic. Proposal: book " + treatment.name() + " with " + therapist.name() + " on " + day + " at 10:00 in the Duck Physical Therapy Clinic.";
        return Json.createObjectBuilder().add("duckId", facts.physioDuckId()).add("treatmentId", treatment.id()).add("therapistId", therapist.id()).add("start", start.toString()).add("text", text).build().toString();
    }

    private static PhysioClient.Option pick(List<PhysioClient.Option> options, JsonObject json, String key) {
        if (json != null && json.containsKey(key) && json.get(key) instanceof jakarta.json.JsonNumber n) {
            for (PhysioClient.Option o : options) if (o.id() == n.longValue()) return o;
        }
        return options.get(0);
    }

    private static boolean near(LocalDate incident, String date) {
        return Math.abs(ChronoUnit.DAYS.between(incident, LocalDate.parse(date))) <= NEARBY_DAYS;
    }

    private static String list(List<String> items) {
        return items.isEmpty() ? "none" : String.join("; ", items);
    }
}
