package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.agent.AgentCheck;
import eu.hansolo.duckinsurance.agent.PhysioBooking;
import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.domain.PolicyStatus;
import eu.hansolo.duckinsurance.domain.Vet;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
import eu.hansolo.duckinsurance.service.PolicyService;
import eu.hansolo.duckinsurance.service.VetService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.context.FacesContext;
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
public class ClaimsBean implements Serializable {

    @Inject
    private ClaimService      claimService;
    @Inject
    private PolicyService     policyService;
    @Inject
    private DuckService       duckService;
    @Inject
    private VetService        vetService;
    @Inject
    private AgentCheck        agentCheck;
    @Inject
    private PhysioBooking     physioBooking;
    @Inject
    private CustomerService   customerService;
    private List<Claim>       claims;
    private List<Policy>      activePolicies;
    private List<Vet>         vets;
    private Map<Long, Policy> policiesById = new HashMap<>();
    private Map<Long, Duck>   ducksById    = new HashMap<>();
    private Map<Long, Vet>    vetsById     = new HashMap<>();
    private Map<Long, String> owners       = new HashMap<>();
    private ClaimStatus       filter;
    private String            note;
    private Long              policyId;
    private Long              vetId;
    private String            description;
    private BigDecimal        amount;
    private LocalDate         incidentDate;


    @PostConstruct
    public void load() {
        claims = claimService.list(filter);
        List<Policy> all = policyService.list();
        all.forEach(p -> policiesById.put(p.getId(), p));
        activePolicies = all.stream().filter(p -> p.getStatus() == PolicyStatus.ACTIVE).toList();
        duckService.list(null).forEach(d -> ducksById.put(d.getId(), d));
        vets = vetService.list();
        vets.forEach(v -> vetsById.put(v.getId(), v));
        customerService.list(null).forEach(c -> owners.put(c.getId(), c.getFullName()));
    }

    public void submit() {
        if (Messages.attempt(() -> claimService.submit(policyId, description, amount, incidentDate, vetId), "Claim submitted - the fraud check runs asynchronously, refresh in a moment")) {
            description = null;
            amount = null;
            incidentDate = null;
            vetId = null;
            load();
        }
    }

    /** Starts the fraud agent for this one claim; it runs in the background, "Refresh" shows the result. */
    public void check(long id) {
        try {
            Messages.info(agentCheck.start(id) ? "The fraud agent is checking the claim, press Refresh in a moment" : "The clinics are not reachable, so the rule-based check was applied");
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Messages.error(root.getMessage() == null ? root.toString() : root.getMessage());
        }
        load();
    }

    private static boolean isCheckable(eu.hansolo.duckinsurance.domain.PolicyType type) {
        return type == eu.hansolo.duckinsurance.domain.PolicyType.VET_CARE || type == eu.hansolo.duckinsurance.domain.PolicyType.PHYSIO;
    }

    /** The agent's proposal for a claim as text, or null. */
    public String proposalText(Claim claim) {
        return PhysioBooking.text(claim.getProposal());
    }

    /** Books the proposed appointment in the physical therapy clinic (adjusters and admins). */
    public void book(long id) {
        if (!isAdjuster()) {
            Messages.error("Only adjusters can book the proposed appointment");
            return;
        }
        try {
            Messages.info("Booked " + physioBooking.book(id) + " in the Duck Physical Therapy Clinic");
        } catch (RuntimeException e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            Messages.error(root.getMessage() == null ? root.toString() : root.getMessage());
        }
        load();
    }

    /** The reason under the fraud score; while the fraud agent is switched off, without the hint to ask it. */
    public String reason(Claim claim) {
        String reason = claim.getFraudReason();
        return reason == null || AgentCheck.enabled() ? reason : reason.replace(" Press Check to compare the claim with the DuckClinic.", "");
    }

    public boolean canCheck(Claim claim) {
        return claim.getStatus().isOpen() && !eu.hansolo.duckinsurance.service.ClaimService.CHECKING.equals(claim.getFraudReason()) && isCheckable(policiesById.get(claim.getPolicyId()).getType()) && AgentCheck.enabled();
    }

    public void approve(long id) {
        decide(id, true);
    }

    public void reject(long id) {
        decide(id, false);
    }

    private void decide(long id, boolean approve) {
        if (Messages.attempt(() -> claimService.decide(id, approve, note), approve ? "Claim approved" : "Claim rejected")) {
            note = null;
            load();
        }
    }

    public boolean isAdjuster() {
        return FacesContext.getCurrentInstance().getExternalContext().isUserInRole("ADJUSTER") || FacesContext.getCurrentInstance().getExternalContext().isUserInRole("ADMIN");
    }

    public String policyNumber(Long policyId) { return policiesById.get(policyId).getPolicyNumber(); }
    public String duck(Long policyId) { Policy policy = policiesById.get(policyId); return ducksById.get(policy.getDuckId()).getName() + " (" + owners.get(policy.getCustomerId()) + ")"; }
    public String vetName(Long vetId) { return vetId == null ? "-" : vetsById.get(vetId).getName(); }
    public String label(Policy policy) { return policy.getPolicyNumber() + " · " + policy.getType().getLabel() + " · " + ducksById.get(policy.getDuckId()).getName(); }
    public ClaimStatus[] getStatuses() { return ClaimStatus.values(); }
    public List<Claim> getClaims() { return claims; }
    public List<Policy> getActivePolicies() { return activePolicies; }
    public List<Vet> getVets() { return vets; }
    public ClaimStatus getFilter() { return filter; }
    public void setFilter(ClaimStatus filter) { this.filter = filter; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long policyId) { this.policyId = policyId; }
    public Long getVetId() { return vetId; }
    public void setVetId(Long vetId) { this.vetId = vetId; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public LocalDate getIncidentDate() { return incidentDate; }
    public void setIncidentDate(LocalDate incidentDate) { this.incidentDate = incidentDate; }
}
