package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


/**
 * Patterns across the insurer's own claims, computed in code. A language model is unreliable at counting and comparing many records, so the code finds the patterns and the model only weighs them.
 * A signal is a fact, not a verdict: an honest owner can have two claims in a row, which is why the fraud agent weighs the signals against what the clinics have on record.
 * The thresholds are constants of this class. The public methods without "signals" in their name are the checks the agent may ask for (see {@link FraudInvestigation}).
 */
@ApplicationScoped
public class PatternSignals {
    /** Claims within this many days of the incident count as "recent" for the repeat-claim signals. */
    public static final int    RECENT_DAYS        = 90;
    /** Two claims for the same duck this close together look like one treatment billed twice or split up. */
    public static final int    SAME_TREATMENT_DAYS = 7;
    /** Amounts that differ by at most this percentage count as the same invoice. */
    public static final int    SAME_AMOUNT_PERCENT = 10;
    /** An incident this soon after the policy started is a signal. */
    public static final int    EARLY_DAYS         = 30;
    /** An amount of at least this percentage of the coverage is a signal. */
    public static final int    NEAR_LIMIT_PERCENT = 90;
    /** An amount of at least this many times the median of the other claims of the same policy type is a signal. */
    public static final int    HIGH_AMOUNT_FACTOR = 2;
    /** Word overlap (0 to 1) from which two descriptions count as the same wording. */
    public static final double SAME_WORDING       = 0.6;

    @Inject
    private ClaimService    claims;
    @Inject
    private PolicyService   policies;
    @Inject
    private DuckService     ducks;
    @Inject
    private CustomerService customers;

    /** One pattern found in the data. code is stable (for the log and the reason), text is a sentence for the model and the adjuster. */
    public record Signal(String code, String text) {
    }

    /** Everything needed to describe a claim in words, loaded once per question. */
    private record Context(Map<Long, Policy> policies, Map<Long, Duck> ducks, Map<Long, String> owners) {
    }


    /** @return the signals of one claim; empty if the data shows nothing unusual */
    public List<Signal> forClaim(Claim claim) {
        Context      ctx    = context();
        Policy       policy = ctx.policies().get(claim.getPolicyId());
        List<Claim>  others = others(claim);
        List<Signal> result = new ArrayList<>();

        for (Claim c : others) {
            if (similarity(c.getDescription(), claim.getDescription()) < SAME_WORDING) continue;
            boolean sameOwner = ownerOf(c, ctx) == policy.getCustomerId();
            result.add(new Signal("SAME_WORDING", describe(c, ctx) + " has almost the same description" + (sameOwner ? " and belongs to the same owner." : " (owner " + ctx.owners().get(ownerOf(c, ctx)) + ").")));
        }

        List<Claim> sameTreatment = others.stream().filter(c -> sameDuck(c, policy, ctx) && days(c, claim) <= SAME_TREATMENT_DAYS).toList();
        for (Claim c : sameTreatment) {
            if (sameAmount(c.getAmount(), claim.getAmount())) result.add(new Signal("DUPLICATE_INVOICE", "The same duck has a claim " + days(c, claim) + " day(s) apart with almost the same amount, possibly the same invoice twice: " + describe(c, ctx) + "."));
            else result.add(new Signal("SPLIT_INVOICE", "The same duck has another claim only " + days(c, claim) + " day(s) apart, possibly one treatment split into several claims: " + describe(c, ctx) + "."));
        }

        List<Claim> rejected = others.stream().filter(c -> ownerOf(c, ctx) == policy.getCustomerId() && c.getStatus() == ClaimStatus.REJECTED).toList();
        if (!rejected.isEmpty()) result.add(new Signal("PRIOR_REJECTION", "The owner has " + rejected.size() + " rejected claim(s): " + rejected.stream().map(c -> c.getClaimNumber() + (c.getDecisionNote() == null ? "" : " (" + c.getDecisionNote() + ")")).collect(Collectors.joining("; ")) + "."));

        List<Claim> sameDuck = others.stream().filter(c -> sameDuck(c, policy, ctx) && days(c, claim) > SAME_TREATMENT_DAYS && days(c, claim) <= RECENT_DAYS).toList();
        if (!sameDuck.isEmpty()) result.add(new Signal("REPEAT_DUCK", "The same duck has " + sameDuck.size() + " other claim(s) within " + RECENT_DAYS + " days of this incident: " + sameDuck.stream().map(c -> describe(c, ctx)).collect(Collectors.joining("; ")) + "."));

        List<Claim> otherDucks = others.stream().filter(c -> ownerOf(c, ctx) == policy.getCustomerId() && !sameDuck(c, policy, ctx) && days(c, claim) <= RECENT_DAYS).toList();
        if (!otherDucks.isEmpty()) result.add(new Signal("REPEAT_OWNER", "The owner has " + otherDucks.size() + " claim(s) for other ducks within " + RECENT_DAYS + " days: " + otherDucks.stream().map(c -> describe(c, ctx)).collect(Collectors.joining("; ")) + "."));

        long sinceStart = ChronoUnit.DAYS.between(policy.getStartDate(), claim.getIncidentDate());
        if (sinceStart < EARLY_DAYS) result.add(new Signal("EARLY_CLAIM", "The incident was " + sinceStart + " day(s) after the policy started."));

        int ratio = claim.getAmount().multiply(BigDecimal.valueOf(100)).divide(policy.getCoverageAmount(), 0, RoundingMode.HALF_UP).intValue();
        if (ratio >= NEAR_LIMIT_PERCENT) result.add(new Signal("NEAR_LIMIT", "The amount is " + ratio + "% of the coverage of " + policy.getCoverageAmount() + " EUR."));

        List<BigDecimal> sameType = others.stream().filter(c -> ctx.policies().get(c.getPolicyId()).getType() == policy.getType()).map(Claim::getAmount).sorted().toList();
        if (sameType.size() >= 3) {
            BigDecimal median = sameType.get(sameType.size() / 2);
            if (median.signum() > 0 && claim.getAmount().compareTo(median.multiply(BigDecimal.valueOf(HIGH_AMOUNT_FACTOR))) >= 0) {
                result.add(new Signal("HIGH_AMOUNT", "The amount is " + claim.getAmount().divide(median, 1, RoundingMode.HALF_UP) + " times the median of " + median + " EUR of the other " + sameType.size() + " " + policy.getType() + " claims."));
            }
        }
        return result;
    }

    /** The signals as lines for a prompt or a log. */
    public static String text(List<Signal> signals) {
        return signals.isEmpty() ? "none" : signals.stream().map(s -> "\n- " + s.code() + ": " + s.text()).collect(Collectors.joining());
    }

    /** The signal codes, for the reason shown to the adjuster. */
    public static String codes(List<Signal> signals) {
        return signals.stream().map(Signal::code).distinct().collect(Collectors.joining(", "));
    }


    // ---- checks the agent may ask for ----

    /** All other claims of the owner of this claim's duck, on any duck and at any time. */
    public String ownerClaims(Claim claim) {
        Context     ctx    = context();
        Policy      policy = ctx.policies().get(claim.getPolicyId());
        List<Claim> found  = others(claim).stream().filter(c -> ownerOf(c, ctx) == policy.getCustomerId()).toList();
        return found.isEmpty() ? "The owner has no other claims." : found.stream().map(c -> describe(c, ctx)).collect(Collectors.joining("\n"));
    }

    /** The claims of any owner whose wording is at least loosely similar, closest first. */
    public String similarClaims(Claim claim) {
        Context     ctx   = context();
        List<Claim> found = others(claim).stream().filter(c -> similarity(c.getDescription(), claim.getDescription()) >= 0.3).sorted(Comparator.comparingDouble((Claim c) -> similarity(c.getDescription(), claim.getDescription())).reversed()).limit(5).toList();
        return found.isEmpty() ? "No other claim has a similar description." : found.stream().map(c -> describe(c, ctx) + ", owner " + ctx.owners().get(ownerOf(c, ctx)) + ", wording match " + Math.round(similarity(c.getDescription(), claim.getDescription()) * 100) + "%").collect(Collectors.joining("\n"));
    }

    /** The owner's policies with their start dates: several policies started shortly before claims are a pattern of their own. */
    public String ownerPolicies(Claim claim) {
        Context ctx    = context();
        Policy  policy = ctx.policies().get(claim.getPolicyId());
        return policies.forCustomer(policy.getCustomerId()).stream().map(p -> p.getPolicyNumber() + " (" + p.getType() + ", " + ctx.ducks().get(p.getDuckId()).getName() + "): coverage " + p.getCoverageAmount() + " EUR, started " + p.getStartDate() + ", " + p.getStatus()).collect(Collectors.joining("\n"));
    }


    /** Other claims that name the same vet, with their owners: one vet behind many claims of different owners is a pattern of its own. */
    public String sameVetClaims(Claim claim) {
        if (claim.getVetId() == null) return "The claim names no vet.";
        Context     ctx   = context();
        List<Claim> found = others(claim).stream().filter(c -> claim.getVetId().equals(c.getVetId())).toList();
        return found.isEmpty() ? "No other claim names this vet." : found.stream().map(c -> describe(c, ctx) + ", owner " + ctx.owners().get(ownerOf(c, ctx))).collect(Collectors.joining("\n"));
    }


    // ---- helpers ----

    private Context context() {
        Map<Long, Policy> policyById = new HashMap<>();
        for (Policy p : policies.list()) policyById.put(p.getId(), p);
        Map<Long, Duck> duckById = new HashMap<>();
        for (Duck d : ducks.list(null)) duckById.put(d.getId(), d);
        Map<Long, String> ownerById = new HashMap<>();
        for (Policy p : policyById.values()) ownerById.computeIfAbsent(p.getCustomerId(), id -> customers.get(id).getLastName());
        return new Context(policyById, duckById, ownerById);
    }

    private List<Claim> others(Claim claim) {
        return claims.list(null).stream().filter(c -> !c.getId().equals(claim.getId())).toList();
    }

    private static long ownerOf(Claim claim, Context ctx) {
        return ctx.policies().get(claim.getPolicyId()).getCustomerId();
    }

    private static boolean sameDuck(Claim other, Policy policy, Context ctx) {
        return ctx.policies().get(other.getPolicyId()).getDuckId().equals(policy.getDuckId());
    }

    private static long days(Claim other, Claim claim) {
        return Math.abs(ChronoUnit.DAYS.between(other.getIncidentDate(), claim.getIncidentDate()));
    }

    static boolean sameAmount(BigDecimal a, BigDecimal b) {
        BigDecimal larger = a.max(b);
        return larger.signum() > 0 && a.subtract(b).abs().multiply(BigDecimal.valueOf(100)).compareTo(larger.multiply(BigDecimal.valueOf(SAME_AMOUNT_PERCENT))) <= 0;
    }

    private static String describe(Claim c, Context ctx) {
        String duck = ctx.ducks().get(ctx.policies().get(c.getPolicyId()).getDuckId()).getName();
        String text = c.getDescription().length() > 90 ? c.getDescription().substring(0, 90) + "..." : c.getDescription();
        return c.getClaimNumber() + " (" + duck + ", incident " + c.getIncidentDate() + ", " + c.getAmount() + " EUR, " + c.getStatus() + "): \"" + text + "\"";
    }

    /** Word overlap of two descriptions: shared words divided by all words, ignoring case and words of three letters or less. 0 means nothing in common, 1 the same words. */
    static double similarity(String a, String b) {
        Set<String> wordsA = words(a);
        Set<String> wordsB = words(b);
        if (wordsA.isEmpty() || wordsB.isEmpty()) return 0;
        Set<String> shared = new HashSet<>(wordsA);
        shared.retainAll(wordsB);
        Set<String> all = new HashSet<>(wordsA);
        all.addAll(wordsB);
        return (double) shared.size() / all.size();
    }

    private static Set<String> words(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) return result;
        for (String w : text.toLowerCase().split("[^\\p{L}]+")) if (w.length() > 3) result.add(w);
        return result;
    }
}
