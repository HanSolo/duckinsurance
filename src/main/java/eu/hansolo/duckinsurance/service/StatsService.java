package eu.hansolo.duckinsurance.service;

import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.domain.PolicyStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class StatsService {

    public record Stats(long owners, long ducks, long activePolicies, BigDecimal annualPremium, long openClaims, Map<ClaimStatus, Long> claimsByStatus) {
    }

    @Inject
    private CustomerService customers;
    @Inject
    private DuckService     ducks;
    @Inject
    private PolicyService   policies;
    @Inject
    private ClaimService    claims;


    public Stats current() {
        List<Policy> active = policies.list().stream().filter(p -> p.getStatus() == PolicyStatus.ACTIVE).toList();
        BigDecimal premium = active.stream().map(Policy::getAnnualPremium).reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<ClaimStatus, Long> byStatus = new EnumMap<>(ClaimStatus.class);
        for (ClaimStatus status : ClaimStatus.values()) byStatus.put(status, claims.count(status));
        long open = byStatus.entrySet().stream().filter(e -> e.getKey().isOpen()).mapToLong(Map.Entry::getValue).sum();
        return new Stats(customers.list(null).size(), ducks.list(null).size(), active.size(), premium, open, byStatus);
    }
}
