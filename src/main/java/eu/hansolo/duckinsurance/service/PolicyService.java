package eu.hansolo.duckinsurance.service;

import eu.hansolo.duckinsurance.audit.Audited;
import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.domain.PolicyStatus;
import eu.hansolo.duckinsurance.domain.PolicyType;
import eu.hansolo.duckinsurance.repo.Policies;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

@ApplicationScoped
@Transactional
public class PolicyService {

    @Inject
    private Policies          policies;
    @Inject
    private DuckService       ducks;
    @Inject
    private CustomerService   customers;
    @Inject
    private PremiumCalculator calculator;


    public List<Policy> list() {
        try (Stream<Policy> all = policies.findAll()) { return all.sorted(Comparator.comparing(Policy::getId)).toList(); }
    }

    public Policy get(long id) {
        return policies.findById(id).orElseThrow(() -> DomainException.notFound("Policy " + id));
    }

    public List<Policy> forCustomer(long customerId) {
        return policies.byCustomer(customerId);
    }

    public BigDecimal quote(long duckId, PolicyType type, BigDecimal coverage) {
        return calculator.annualPremium(type, coverage, ducks.get(duckId));
    }

    @Audited
    public Policy issue(long duckId, PolicyType type, BigDecimal coverage, LocalDate start) {
        Duck duck = ducks.get(duckId);
        Customer owner = customers.get(duck.getOwnerId());
        if (owner.getAge() < 18) throw DomainException.conflict("Owners under 18 cannot hold a policy. Please ask a grown-up duck owner");
        Policy policy = new Policy();
        policy.setCustomerId(owner.getId());
        policy.setDuckId(duckId);
        policy.setType(type);
        policy.setCoverageAmount(coverage);
        policy.setAnnualPremium(calculator.annualPremium(type, coverage, duck));
        policy.setStartDate(start);
        policy.setEndDate(start.plusYears(1));
        policy.setStatus(PolicyStatus.ACTIVE);
        return policies.save(policy);
    }

    @Audited
    public Policy cancel(long id) {
        Policy policy = get(id);
        if (policy.getStatus() != PolicyStatus.ACTIVE) throw DomainException.conflict("Only active policies can be cancelled");
        policy.setStatus(PolicyStatus.CANCELLED);
        return policies.save(policy);
    }

    /** Called by the scheduled job. */
    public int expireOverdue() {
        List<Policy> overdue = policies.byStatus(PolicyStatus.ACTIVE).stream().filter(p -> p.getEndDate().isBefore(LocalDate.now())).toList();
        overdue.forEach(p -> { p.setStatus(PolicyStatus.EXPIRED); policies.save(p); });
        return overdue.size();
    }
}
