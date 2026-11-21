package eu.hansolo.duckinsurance.agent;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;


/** The pre-authorizations the insurer has given, and the answers of running agent workflows. In memory only. */
@ApplicationScoped
public class PreauthStore {

    /** A pre-authorization. Only APPROVED and PARTIAL ones reserve coverage. */
    public record Preauth(String reference, String referralId, long policyId, String policyNumber, String duckName, String status, int approvedSessions, BigDecimal approvedPerSession, BigDecimal approvedTotal, int claimedSessions, BigDecimal claimedAmount, String reason, LocalDateTime at) {

        /** Coverage that is promised but not yet claimed. */
        public BigDecimal reservation() {
            return status.equals("APPROVED") || status.equals("PARTIAL") ? approvedTotal.subtract(claimedAmount).max(BigDecimal.ZERO) : BigDecimal.ZERO;
        }
    }

    private final Map<String, Preauth>      byReferral = new ConcurrentHashMap<>();
    private final Map<String, PreauthAnswer> answers    = new ConcurrentHashMap<>();
    private final AtomicInteger             numbers    = new AtomicInteger();


    public void putAnswer(String correlation, PreauthAnswer answer) {
        answers.put(correlation, answer);
    }

    public PreauthAnswer takeAnswer(String correlation) {
        return answers.remove(correlation);
    }

    /** Remembers the answer of a request as the pre-authorization of its referral. NEEDS_INFO is not remembered. */
    public Preauth save(PreauthRequest request, PreauthAnswer answer) {
        if (answer.status().equals("NEEDS_INFO")) return null;
        Preauth previous = byReferral.get(request.referralId());
        String reference = previous != null ? previous.reference() : "PA-%05d".formatted(numbers.incrementAndGet());
        Preauth preauth = new Preauth(reference, request.referralId(), answer.policyId(), answer.policyNumber(), request.duckName(), answer.status(), answer.approvedSessions(), answer.approvedPerSession(), answer.approvedTotal(), 0, BigDecimal.ZERO, answer.reason(), LocalDateTime.now());
        byReferral.put(request.referralId(), preauth);
        return preauth;
    }

    public Preauth get(String referralId) {
        return byReferral.get(referralId);
    }

    public Preauth recordClaim(String referralId, BigDecimal amount) {
        return byReferral.computeIfPresent(referralId, (id, p) -> new Preauth(p.reference(), p.referralId(), p.policyId(), p.policyNumber(), p.duckName(), p.status(), p.approvedSessions(), p.approvedPerSession(), p.approvedTotal(), p.claimedSessions() + 1, p.claimedAmount().add(amount), p.reason(), p.at()));
    }

    /**
     * Cancels a pre-authorization. Sessions that were claimed stay claimed, so the approved sessions and the approved total shrink to what was claimed and nothing remains reserved.
     * @return the cancelled pre-authorization, or null if the referral has none
     */
    public Preauth cancel(String referralId, String reason) {
        return byReferral.computeIfPresent(referralId, (id, p) -> new Preauth(p.reference(), p.referralId(), p.policyId(), p.policyNumber(), p.duckName(), "CANCELLED", p.claimedSessions(), p.approvedPerSession(), p.claimedAmount(), p.claimedSessions(), p.claimedAmount(), reason, LocalDateTime.now()));
    }

    /** Coverage of one policy that is promised to clinics but not yet claimed. */
    public BigDecimal reserved(long policyId) {
        return byReferral.values().stream().filter(p -> p.policyId() == policyId).map(Preauth::reservation).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<Preauth> all() {
        return List.copyOf(byReferral.values());
    }

    public void clear() {
        byReferral.clear();
        answers.clear();
    }
}
