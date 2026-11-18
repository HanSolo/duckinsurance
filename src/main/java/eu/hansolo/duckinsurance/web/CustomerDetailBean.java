package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The "owner page" of this demo: one owner with their ducks, policies and claims. */
@Named
@ViewScoped
public class CustomerDetailBean implements Serializable {

    @Inject
    private CustomerService   customerService;
    @Inject
    private DuckService       duckService;
    @Inject
    private PolicyService     policyService;
    @Inject
    private ClaimService      claimService;
    private Long              id;
    private Customer          customer;
    private List<Duck>        ducks;
    private List<Policy>      policies;
    private List<Claim>       claims;
    private Map<Long, String> duckNames = new HashMap<>();


    /** Reads ?id= directly, so the page works even if the viewParam / viewAction pair is not processed. */
    @PostConstruct
    void init() {
        String param = FacesContext.getCurrentInstance().getExternalContext().getRequestParameterMap().get("id");
        if (param != null && !param.isBlank()) id = Long.valueOf(param.trim());
        load();
    }

    public void load() {
        if (id == null) return;
        customer = customerService.get(id);
        ducks = duckService.list(id);
        ducks.forEach(d -> duckNames.put(d.getId(), d.getName()));
        policies = policyService.forCustomer(id);
        claims = claimService.forCustomer(id);
    }

    public String duckName(Long duckId) { return duckNames.get(duckId); }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Customer getCustomer() { return customer; }
    public List<Duck> getDucks() { return ducks; }
    public List<Policy> getPolicies() { return policies; }
    public List<Claim> getClaims() { return claims; }
}
