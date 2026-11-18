package eu.hansolo.duckinsurance.service;

import eu.hansolo.duckinsurance.audit.Audited;
import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.domain.PolicyStatus;
import eu.hansolo.duckinsurance.event.ClaimEvent;
import eu.hansolo.duckinsurance.repo.Claims;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.security.enterprise.SecurityContext;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
@Transactional
public class ClaimService {

    @Inject
    private Claims            claims;
    @Inject
    private PolicyService     policies;
    @Inject
    private VetService        vets;
    @Inject
    private Event<ClaimEvent> events;
    @Inject
    private SecurityContext   securityContext;


    public List<Claim> list(ClaimStatus status) {
        List<Claim> result;
        if (status == null) {
            try (Stream<Claim> all = claims.findAll()) { result = all.toList(); }
        } else {
            result = claims.byStatus(status);
        }
        return result.stream().sorted(Comparator.comparing(Claim::getId).reversed()).toList();
    }

    public Claim get(long id) {
        return claims.findById(id).orElseThrow(() -> DomainException.notFound("Claim " + id));
    }

    public List<Claim> forCustomer(long customerId) {
        return policies.forCustomer(customerId).stream().flatMap(p -> claims.byPolicy(p.getId()).stream()).sorted(Comparator.comparing(Claim::getId).reversed()).toList();
    }

    public long count(ClaimStatus status) {
        return claims.countByStatus(status);
    }

    @Audited
    public Claim submit(long policyId, String description, BigDecimal amount, LocalDate incidentDate, Long vetId) {
        Policy policy = policies.get(policyId);
        if (vetId != null) vets.get(vetId);
        if (policy.getStatus() != PolicyStatus.ACTIVE) throw DomainException.conflict("Claims can only be filed against active policies");
        if (amount.compareTo(policy.getCoverageAmount()) > 0) throw DomainException.conflict("Claim amount exceeds the coverage of " + policy.getCoverageAmount());
        if (incidentDate.isBefore(policy.getStartDate()) || incidentDate.isAfter(LocalDate.now())) throw DomainException.conflict("Incident date is outside the policy period");
        Claim claim = new Claim();
        claim.setPolicyId(policyId);
        claim.setVetId(vetId);
        claim.setDescription(description);
        claim.setAmount(amount);
        claim.setIncidentDate(incidentDate);
        claim.setSubmittedAt(LocalDateTime.now());
        claim.setStatus(ClaimStatus.SUBMITTED);
        Claim saved = claims.save(claim);
        publish(saved.getId(), null, ClaimStatus.SUBMITTED);
        return saved;
    }

    @Audited
    public Claim decide(long id, boolean approve, String note) {
        if (!securityContext.isCallerInRole("ADJUSTER") && !securityContext.isCallerInRole("ADMIN")) throw DomainException.forbidden("Only adjusters can decide claims");
        Claim claim = get(id);
        if (!claim.getStatus().isOpen()) throw DomainException.conflict("Claim is already " + claim.getStatus());
        ClaimStatus before = claim.getStatus();
        claim.setStatus(approve ? ClaimStatus.APPROVED : ClaimStatus.REJECTED);
        claim.setDecisionNote(note);
        claim.setDecidedBy(securityContext.getCallerPrincipal().getName());
        Claim saved = claims.save(claim);
        publish(id, before, saved.getStatus());
        return saved;
    }

    /** Runs on a virtual thread, started after the submitting transaction committed. */
    @Audited
    public void runFraudCheck(long id) {
        Claim claim = get(id);
        if (claim.getStatus() != ClaimStatus.SUBMITTED) return;
        BigDecimal coverage = policies.get(claim.getPolicyId()).getCoverageAmount();
        int ratio = claim.getAmount().multiply(BigDecimal.valueOf(100)).divide(coverage, 0, RoundingMode.HALF_UP).intValue();
        int shortText = claim.getDescription().length() < 25 ? 25 : 0;
        claim.setFraudScore(Math.min(100, ratio + shortText));
        claim.setStatus(ClaimStatus.UNDER_REVIEW);
        claims.save(claim);
        publish(id, ClaimStatus.SUBMITTED, ClaimStatus.UNDER_REVIEW);
    }

    /** The fraud agent's result: score and reason on the claim, then to review. */
    @Audited
    public void applyVerdict(long id, int score, String reason) {
        Claim claim = get(id);
        if (claim.getStatus() != ClaimStatus.SUBMITTED) return;
        claim.setFraudScore(Math.max(0, Math.min(100, score)));
        claim.setFraudReason(reason.length() > 1000 ? reason.substring(0, 1000) : reason);
        claim.setStatus(ClaimStatus.UNDER_REVIEW);
        claims.save(claim);
        publish(id, ClaimStatus.SUBMITTED, ClaimStatus.UNDER_REVIEW);
    }

    private void publish(Long id, ClaimStatus from, ClaimStatus to) {
        String actor = securityContext.getCallerPrincipal() == null ? "system" : securityContext.getCallerPrincipal().getName();
        ClaimEvent event = new ClaimEvent(id, from, to, actor);
        events.fire(event);
        events.fireAsync(event);
    }
}
