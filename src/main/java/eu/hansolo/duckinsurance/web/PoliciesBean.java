package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.domain.PolicyType;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Named
@ViewScoped
public class PoliciesBean implements Serializable {

    @Inject
    private PolicyService     policyService;
    @Inject
    private DuckService       duckService;
    @Inject
    private CustomerService   customerService;
    private List<Policy>      policies;
    private List<Duck>        ducks;
    private Map<Long, Duck>   ducksById = new HashMap<>();
    private Map<Long, String> owners    = new HashMap<>();
    private Long              duckId;
    private PolicyType        type      = PolicyType.VET_CARE;
    private BigDecimal        coverage  = new BigDecimal("5000");
    private LocalDate         startDate;
    private BigDecimal        quote;


    @PostConstruct
    public void load() {
        policies = policyService.list();
        ducks = duckService.list(null);
        ducks.forEach(d -> ducksById.put(d.getId(), d));
        customerService.list(null).forEach(c -> owners.put(c.getId(), c.getFullName()));
    }

    public void preview() {
        Messages.attempt(() -> quote = policyService.quote(duckId, type, coverage), "Premium calculated");
    }

    public void issue() {
        if (Messages.attempt(() -> policyService.issue(duckId, type, coverage, startDate == null ? LocalDate.now() : startDate), "Policy issued")) {
            quote = null;
            load();
        }
    }

    public void cancel(long id) {
        if (Messages.attempt(() -> policyService.cancel(id), "Policy cancelled")) load();
    }

    public String duckName(Long duckId) { return ducksById.get(duckId).getName(); }
    public String ownerName(Long customerId) { return owners.get(customerId); }
    public String duckLabel(Duck duck) { return duck.getName() + " · " + duck.getBreed().getLabel() + " · " + owners.get(duck.getOwnerId()); }
    public PolicyType[] getTypes() { return PolicyType.values(); }
    public List<Policy> getPolicies() { return policies; }
    public List<Duck> getDucks() { return ducks; }
    public Long getDuckId() { return duckId; }
    public void setDuckId(Long duckId) { this.duckId = duckId; }
    public PolicyType getType() { return type; }
    public void setType(PolicyType type) { this.type = type; }
    public BigDecimal getCoverage() { return coverage; }
    public void setCoverage(BigDecimal coverage) { this.coverage = coverage; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public BigDecimal getQuote() { return quote; }
}
