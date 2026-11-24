package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.config.AppConfig;
import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


/**
 * The investigation the fraud agent may ask for. The model does not call anything itself: it names up to {@value #MAX_CHECKS} checks from a fixed list, and this class runs them in code.
 * So the agent decides what to look at, the code decides what can be looked at. The checks only read; nothing is changed and no other claim is shown beyond what an adjuster sees anyway.
 * Only runs while the fraud agent runs, so the Fraud demo switch covers it. DUCK_FRAUD_INVESTIGATE=off turns only the second round off (the agent then judges after one model call, as before).
 */
@ApplicationScoped
public class FraudInvestigation {
    public static final int MAX_CHECKS = 3;

    /** The checks, with the sentence the model sees and the label of the log. */
    public enum Check {
        OWNER_CLAIMS("every other claim of this owner, on any duck"),
        SIMILAR_CLAIMS("claims of any owner with a similar description"),
        OWNER_POLICIES("the owner's policies with their start dates"),
        OWNER_OTHER_DUCKS("the visits of the owner's other ducks at the DuckClinic"),
        SAME_VET_CLAIMS("other claims that name the same vet");

        private final String label;

        Check(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Inject
    private PatternSignals  patterns;
    @Inject
    private PolicyService   policies;
    @Inject
    private DuckService     ducks;
    @Inject
    private CustomerService customers;
    @Inject
    private ClinicClient    clinic;


    public static boolean enabled() {
        return !"off".equalsIgnoreCase(AppConfig.get("DUCK_FRAUD_INVESTIGATE", "on"));
    }

    /** The checks as the model reads them in the prompt: "NAME (what it shows), ...". */
    public static String menu() {
        return Arrays.stream(Check.values()).map(c -> c.name() + " (" + c.label() + ")").collect(Collectors.joining(", "));
    }

    /**
     * The checks the model asked for in its first answer: the "investigate" list of the JSON, only known names, no duplicates, at most {@value #MAX_CHECKS}.
     * Anything else the model writes there is ignored, which is the point of a fixed list.
     */
    public static List<Check> requested(String answer) {
        List<Check> result = new ArrayList<>();
        try {
            int from = answer.indexOf('{'), to = answer.lastIndexOf('}');
            JsonObject json = Json.createReader(new StringReader(answer.substring(from, to + 1))).readObject();
            JsonArray  list = json.containsKey("investigate") && json.get("investigate") instanceof JsonArray array ? array : null;
            if (list == null) return result;
            for (JsonValue value : list) {
                if (!(value instanceof jakarta.json.JsonString text)) continue;
                for (Check check : Check.values()) {
                    if (check.name().equalsIgnoreCase(text.getString().trim()) && !result.contains(check) && result.size() < MAX_CHECKS) result.add(check);
                }
            }
        } catch (RuntimeException e) {
            // an answer that cannot be read asks for nothing
        }
        return result;
    }

    /** Runs one check for a claim and returns what it found, as text for the model and the log. */
    public String run(Check check, Claim claim) {
        return switch (check) {
            case OWNER_CLAIMS      -> patterns.ownerClaims(claim);
            case SIMILAR_CLAIMS    -> patterns.similarClaims(claim);
            case OWNER_POLICIES    -> patterns.ownerPolicies(claim);
            case OWNER_OTHER_DUCKS -> otherDucks(claim);
            case SAME_VET_CLAIMS   -> patterns.sameVetClaims(claim);
        };
    }

    /** For every other duck of the owner: what the DuckClinic has on record. A clinic that cannot be reached is unknown, not suspicious. */
    private String otherDucks(Claim claim) {
        Policy         policy = policies.get(claim.getPolicyId());
        Customer       owner  = customers.get(policy.getCustomerId());
        List<Duck>     others = ducks.list(owner.getId()).stream().filter(d -> !d.getId().equals(policy.getDuckId())).toList();
        if (others.isEmpty()) return "The owner has no other duck.";
        List<String> lines = new ArrayList<>();
        try {
            for (Duck duck : others) {
                var found = clinic.findDuck(owner.getLastName(), duck.getName());
                if (found.isEmpty()) {
                    lines.add(duck.getName() + ": not known to the DuckClinic.");
                    continue;
                }
                List<String> visits = clinic.visits(found.get().id());
                lines.add(duck.getName() + ": " + (visits.isEmpty() ? "known, no visits." : visits.size() + " visit(s), latest " + String.join("; ", visits.stream().limit(3).toList()) + "."));
            }
        } catch (ClinicUnavailableException e) {
            return "The DuckClinic could not be reached, so nothing is known about the owner's other ducks.";
        }
        return String.join("\n", lines);
    }
}
