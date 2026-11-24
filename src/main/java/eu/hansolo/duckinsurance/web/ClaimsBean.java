package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.agent.AgentCheck;
import eu.hansolo.duckinsurance.agent.FraudTrail;
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
    @Inject
    private FraudTrail        fraudTrail;
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

    // ---- collapsible check result: a short line, the full reason and the agent's trail on demand ----

    private static final int SHORT_REASON = 90;

    /** What the fraud agent found and checked for this claim (only while the Fraud switch is on), or null. */
    public FraudTrail.Trail trail(Claim claim) {
        if (!AgentCheck.enabled()) return null;
        FraudTrail.Trail trail = fraudTrail.get(claim.getId());
        return trail == null || trail.isEmpty() ? null : trail;
    }

    /** Long reasons and reasons with a trail are shown collapsed, so that the table keeps its row height. */
    public boolean collapsible(Claim claim) {
        String reason = reason(claim);
        return reason != null && (reason.length() > SHORT_REASON || trail(claim) != null);
    }

    /** The reason without the [signals: ...] [checked: ...] suffix, which the details show as lists. */
    public String fullReason(Claim claim) {
        String reason = reason(claim);
        if (reason == null) return null;
        int cut = reason.indexOf(" [signals: ");
        if (cut < 0) cut = reason.indexOf(" [checked: ");
        return cut < 0 ? reason : reason.substring(0, cut);
    }

    /** The first sentence of the reason, cut to one line, for the collapsed view. */
    public String shortReason(Claim claim) {
        String reason = fullReason(claim);
        if (reason == null) return null;
        int end = reason.indexOf(". ");
        String first = end > 0 ? reason.substring(0, end + 1) : reason;
        return first.length() > SHORT_REASON ? first.substring(0, SHORT_REASON - 1).stripTrailing() + "…" : first;
    }

    /** "3 signals · 2 checks" for the collapsed view, or an empty text without a trail. */
    public String trailSummary(Claim claim) {
        FraudTrail.Trail trail = trail(claim);
        if (trail == null) return "";
        int s = trail.signals().size(), c = trail.findings().size();
        return (s == 1 ? "1 signal" : s + " signals") + " · " + (c == 1 ? "1 check" : c + " checks");
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
