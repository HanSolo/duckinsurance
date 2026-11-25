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
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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
    @Inject
    private PatternSignals     patterns;
    @Inject
    private FraudInvestigation investigation;
    @Inject
    private FraudTrail         trail;
    @Inject
    private AgentLog           log;


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
        log.say(claim.getClaimNumber(), null, "THOUGHT", "Checking claim " + claim.getClaimNumber() + " (" + claim.getAmount() + " EUR, " + duck.getName() + ", " + owner.getLastName() + "): asking the DuckClinic and the Duck Physical Therapy Clinic what they have on record.");

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
        if (!vetUp && !physioUp) {
            // Neither clinic answers: a claim that blames the weather can still be checked against the weather; any other claim gets the rule-based check
            if (!weatherCanHelp(claim.getDescription())) throw new ClinicUnavailableException("Neither clinic is reachable", null);
            log.say(claim.getClaimNumber(), null, "THOUGHT", "Neither clinic is reachable. The claim blames the weather, so the agent goes on and checks the weather instead.");
        }
        return new ClinicFacts(claim.getId(), text, policy.getType().name(), claim.getDescription(), duck.getId(), vetUp, vetKnown, vetVisits, vetNearby, physioUp, physioDuckId, physioAppts, physioNearby, treatments, therapists);
    }

    /** Can the weather check say something about this claim? The description names a weather event, the weather check is on and the investigation round runs. */
    static boolean weatherCanHelp(String description) {
        return WeatherClient.enabled() && FraudInvestigation.enabled() && WeatherCheck.claimedEvent(description).isPresent();
    }

    /** Always continues the workflow: the verdict travels in the details of the result, a high score is not a reason to stop. */
    @Decision
    Result judge(ClinicFacts facts) {
        Claim                       claim   = claims.get(facts.claimId());
        String                      number  = claim.getClaimNumber();
        List<PatternSignals.Signal> signals = new ArrayList<>(patterns.forClaim(claim));
        boolean                     deeper  = FraudInvestigation.enabled();
        // A claim that blames the weather can be checked against the real weather (public weather MCP server, see WeatherCheck)
        var                         blamed  = WeatherCheck.claimedEvent(claim.getDescription());
        boolean                     weather = deeper && blamed.isPresent() && FraudInvestigation.available().contains(FraudInvestigation.Check.WEATHER);
        if (weather) signals.add(new PatternSignals.Signal("WEATHER_EVENT", "The description blames " + blamed.get().words() + ". The WEATHER check can compare that with the real weather in the owner's town on the incident date."));
        String prompt = """
                You are a fraud analyst of a duck insurer. Compare the claim with the records of two clinics and judge whether the claim makes sense:
                the DuckClinic (veterinary visits) and the Duck Physical Therapy Clinic (therapy appointments such as hydrotherapy, treadmill, wing mobilisation).
                Consider: does a clinic know the duck, is there a visit or appointment near the incident date, does its description fit the claim, is the amount plausible for that treatment.
                Surgery and medical treatment belong to the veterinary clinic, therapy and rehabilitation to the physical therapy clinic. A missing record in the clinic that fits the claim is suspicious, a matching record is a good sign.
                A clinic that could not be reached tells nothing; do not hold it against the claim.
                Answer only with JSON: {"score": <0 plausible .. 100 very suspicious>, "reason": "<one or two sentences>", "treatmentId": <id from the list below that fits the claim best, or null>, "therapistId": <id or null>%s}

                %s

                DuckClinic: %s
                Veterinary visits of the duck: %s
                Visits within %d days of the incident: %s

                Physical therapy clinic: %s
                Appointments of the duck: %s
                Appointments within %d days of the incident: %s
                Treatments (id: name): %s
                Therapists (id: name): %s

                Pattern signals that the insurer's own code found in all claims (facts, each only a hint, not a verdict): %s
                A clinic record that matches the claim outweighs a repeat claim, unless the signal says the same invoice may have been claimed twice. Several signals together, with no matching clinic record, are a strong sign. Do not list the signals in your reason; say what they mean for this claim.
                %s""".formatted(deeper ? ", \"investigate\": [<names of checks, or empty>]" : "",
                              facts.claimText(),
                              !facts.vetReachable() ? "not reachable" : facts.vetDuckKnown() ? "knows this duck" : "does not know this duck",
                              list(facts.vetVisits()), NEARBY_DAYS, list(facts.vetNearby()),
                              !facts.physioReachable() ? "not reachable" : facts.physioDuckId() > 0 ? "knows this duck" : "does not know this duck",
                              list(facts.physioAppts()), NEARBY_DAYS, list(facts.physioNearby()),
                              list(facts.treatments().stream().map(o -> o.id() + ": " + o.text()).toList()), list(facts.therapists().stream().map(o -> o.id() + ": " + o.text()).toList()),
                              PatternSignals.text(signals),
                              deeper ? "If more evidence could change your score, you may ask for up to " + FraudInvestigation.MAX_CHECKS + " of these checks: " + FraudInvestigation.menu() + ". Name them in \"investigate\", otherwise use an empty list.\n" : "");
        LOG.info("Fraud agent: asking the model about claim " + facts.claimId());
        log.say(number, null, "THOUGHT", "The clinics answered; the code found " + (signals.isEmpty() ? "no pattern signals" : signals.size() + " pattern signal(s): " + PatternSignals.codes(signals)) + ". Asking the language model.");
        Answer first = ask(prompt);

        List<FraudInvestigation.Check> wanted = deeper ? new ArrayList<>(FraudInvestigation.requested(first.text())) : new ArrayList<>();
        if (weather && !wanted.contains(FraudInvestigation.Check.WEATHER)) {
            // The insurer's rule: a claim that blames the weather always gets the weather check, even if the model did not ask for it (small models often don't)
            if (wanted.size() >= FraudInvestigation.MAX_CHECKS) wanted.remove(wanted.size() - 1);
            wanted.add(FraudInvestigation.Check.WEATHER);
            log.say(number, null, "THOUGHT", "The claim blames " + blamed.get().words() + " and the model did not ask for the weather, so the insurer's rules add the WEATHER check.");
        }
        if (wanted.isEmpty()) return new Result(true, annotate(parse(first.text(), facts, first.via()), facts.claimId(), signals, List.of(), 1, null, number));

        // The model named checks from a fixed list. The code runs them (read only) and the model answers once more with the evidence.
        StringBuilder                evidence = new StringBuilder();
        List<FraudTrail.Finding>     findings = new ArrayList<>();
        for (FraudInvestigation.Check check : wanted) {
            log.say(number, null, "ACTION", check == FraudInvestigation.Check.WEATHER ? "Asking the public weather MCP server about the weather in the owner's town around the incident date (WEATHER)." : "The model asked to look at " + check.label() + " (" + check.name() + ").");
            String found = investigation.run(check, claim);
            log.say(number, null, "ANSWER", check.name() + ": " + found.replace('\n', ' ').trim());
            findings.add(new FraudTrail.Finding(check.name(), check.label(), found));
            evidence.append("\n").append(check.name()).append(" (").append(check.label()).append("):\n").append(found).append("\n");
        }
        String second = prompt + "\nEvidence you asked for:" + evidence + "\nGive your final answer now in the same JSON format, with \"investigate\": [].\n";
        log.say(number, null, "THOUGHT", "Evidence collected from " + wanted.size() + " check(s). Asking the language model for the final answer.");
        Verdict verdict;
        String  note = null;
        try {
            Answer last = ask(second);
            verdict = parse(last.text(), facts, last.via());
        } catch (RuntimeException e) {
            // e.g. a rate limit on the second call: the first answer was complete, so it is used instead of failing the check
            LOG.info("Fraud agent: the second model call for claim " + facts.claimId() + " failed, using the first answer: " + e.getMessage());
            verdict = new Verdict(0, "", "rules", null);
            String why = String.valueOf(e.getMessage());
            note    = "The second model call failed (" + (why.length() > 160 ? why.substring(0, 160) + "…" : why) + "), so the first answer counts.";
        }
        if (verdict.source().equals("rules")) {
            // the second answer could not be read or did not come: the first one, if it was complete, is better than the fixed rule
            Verdict earlier = parse(first.text(), facts, first.via());
            if (!earlier.source().equals("rules") || note != null) verdict = earlier;
        }
        return new Result(true, annotate(verdict, facts.claimId(), signals, findings, 2, note, number));
    }

    /** Keeps what the code found and what was checked for the claim list, adds a short trail to the reason and writes the verdict to the agent log. */
    private Verdict annotate(Verdict verdict, long claimId, List<PatternSignals.Signal> signals, List<FraudTrail.Finding> findings, int calls, String note, String number) {
        trail.put(claimId, new FraudTrail.Trail(signals, findings, calls, note));
        String reason = verdict.reason() + (signals.isEmpty() ? "" : " [signals: " + PatternSignals.codes(signals) + "]") + (findings.isEmpty() ? "" : " [checked: " + findings.stream().map(FraudTrail.Finding::name).collect(Collectors.joining(", ")) + "]");
        log.say(number, null, "ANSWER", "Score " + verdict.score() + " (" + verdict.source() + "): " + verdict.reason());
        return new Verdict(verdict.score(), reason, verdict.source(), verdict.proposal());
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
