package eu.hansolo.duckinsurance.mcp;

import eu.hansolo.duckinsurance.agent.AgentCheck;
import eu.hansolo.duckinsurance.agent.AgentLog;
import eu.hansolo.duckinsurance.agent.PreauthAnswer;
import eu.hansolo.duckinsurance.agent.PreauthRequest;
import eu.hansolo.duckinsurance.agent.PreauthStore;
import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.api.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.security.enterprise.SecurityContext;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * The tools that other institutions' agents call on the insurer. They are tasks, not CRUD: the insurer's agent decides, and its answer may be "I need more information" or "only partly".
 * Only callers with the role AGENT (the clinics' agents) or ADMIN may use them. Nothing here is confirmed with a human, because the callers are programs and the pre-authorization has no money effect until a claim is filed.
 */
@ApplicationScoped
public class PartnerTools {
    private static final Logger LOG = Logger.getLogger(PartnerTools.class.getName());

    @Inject
    private Event<PreauthRequest> requests;
    @Inject
    private PreauthStore          store;
    @Inject
    private AgentLog              log;
    @Inject
    private ClaimService          claims;
    @Inject
    private AgentCheck            check;
    @Inject
    private SecurityContext       security;


    @McpTool(name = "request_preauthorization", description = "A clinic asks whether DuckInsurance will pay for a treatment plan. The insurer's agent answers APPROVED, PARTIAL (fewer sessions or a lower amount per session), DENIED, or NEEDS_INFO with a list of what is missing (for example referralDocument): then ask again with the missing information. Takes up to a minute because a language model decides.", destructiveHint = McpTool.Hint.FALSE)
    public ToolResult requestPreauthorization(@McpParam(description = "Referral id that all institutions carry, like REF-0001") String referralId, @McpParam(description = "Last name of the duck's owner") String ownerLastName, @McpParam(description = "Name of the duck") String duckName, @McpParam(description = "Name of the clinic that asks") String provider, @McpParam(description = "Name of the planned treatment") String treatmentName, @McpParam(description = "Diagnosis in one or two sentences") String diagnosis, @McpParam(description = "Number of planned sessions") int sessions, @McpParam(description = "Price per session in euro") double pricePerSession, @McpParam(description = "Date of the first session, 2026-12-01") String firstSession, @McpParam(description = "The veterinary referral document. Send it only when the insurer asks for it") Optional<String> referralDocument) {
        requireAgent();
        if (sessions < 1 || pricePerSession <= 0) throw new ToolExecutionException("sessions and pricePerSession must be positive");
        String correlation = UUID.randomUUID().toString();
        PreauthRequest request = new PreauthRequest(correlation, referralId, ownerLastName, duckName, provider, treatmentName, diagnosis, sessions, BigDecimal.valueOf(pricePerSession), date(firstSession), referralDocument.orElse(null));
        try {
            requests.fire(request);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Pre-authorization workflow failed", e);
            throw new ToolExecutionException("The insurer's agent failed: " + e.getMessage());
        }
        PreauthAnswer answer = store.takeAnswer(correlation);
        if (answer == null || answer.status().equals("ERROR")) throw new ToolExecutionException("The insurer's agent gave no answer");
        PreauthStore.Preauth saved = store.get(referralId);
        return ToolResult.structured(json(answer, saved == null || answer.status().equals("NEEDS_INFO") ? null : saved.reference()));
    }

    @McpTool(name = "submit_provider_claim", description = "A clinic bills one completed session of a pre-authorized treatment. The insurer files a claim against the policy (at most the pre-authorized amount per session) and starts its fraud check, which may ask the clinic to confirm the visit.", destructiveHint = McpTool.Hint.FALSE)
    public ToolResult submitProviderClaim(@McpParam(description = "Referral id of the pre-authorization") String referralId, @McpParam(description = "Appointment number of the session at the clinic, like APT-00001") String appointmentNumber, @McpParam(description = "Name of the treatment") String treatmentName, @McpParam(description = "Date of the session, 2026-11-24") String sessionDate, @McpParam(description = "Amount billed in euro") double amount) {
        requireAgent();
        PreauthStore.Preauth pre = store.get(referralId);
        if (pre == null || !(pre.status().equals("APPROVED") || pre.status().equals("PARTIAL"))) throw new ToolExecutionException("There is no approved pre-authorization for " + referralId + ". Ask request_preauthorization first.");
        if (pre.claimedSessions() >= pre.approvedSessions()) throw new ToolExecutionException("All " + pre.approvedSessions() + " pre-authorized sessions of " + referralId + " were claimed already.");
        BigDecimal billed  = BigDecimal.valueOf(amount);
        BigDecimal claimed = billed.min(pre.approvedPerSession());
        String description = "Referral %s: session %d of %d, %s (%s), pre-authorization %s".formatted(referralId, pre.claimedSessions() + 1, pre.approvedSessions(), treatmentName, appointmentNumber, pre.reference()) + (claimed.compareTo(billed) < 0 ? ". Billed " + billed + " EUR, covered up to " + claimed + " EUR." : "");
        Claim claim;
        try {
            claim = claims.submit(pre.policyId(), description, claimed, date(sessionDate), null);
        } catch (DomainException e) {
            throw new ToolExecutionException(e.getMessage());
        }
        store.recordClaim(referralId, claimed);
        String checkNote;
        try {
            checkNote = check.start(claim.getId()) ? " The fraud check is running and will ask the clinics to confirm the visit." : " The clinics are not reachable, so only the rules were applied.";
        } catch (RuntimeException e) {
            checkNote = " The fraud check could not be started: " + e.getMessage();
        }
        log.say(referralId, null, "ACTION", "Claim " + claim.getClaimNumber() + " filed for " + claimed + " EUR (" + description + ")." + checkNote);
        log.say(referralId, "Therapy Agent", "ANSWER", "Claim " + claim.getClaimNumber() + " received for " + claimed + " EUR. An adjuster decides on it.");
        return ToolResult.structured(Json.createObjectBuilder().add("claimNumber", claim.getClaimNumber()).add("claimedAmount", claimed).add("billedAmount", billed).add("status", claim.getStatus().name()).add("remainingSessions", pre.approvedSessions() - pre.claimedSessions() - 1).build());
    }

    @McpTool(name = "get_preauthorization", description = "Shows the pre-authorization that was given for a referral, and how much of it was claimed.", readOnlyHint = McpTool.Hint.TRUE)
    public ToolResult getPreauthorization(@McpParam(description = "Referral id, like REF-0001") String referralId) {
        requireAgent();
        PreauthStore.Preauth p = store.get(referralId);
        if (p == null) throw new ToolExecutionException("There is no pre-authorization for " + referralId);
        return ToolResult.structured(Json.createObjectBuilder().add("reference", p.reference()).add("status", p.status()).add("approvedSessions", p.approvedSessions()).add("approvedPerSession", p.approvedPerSession()).add("claimedSessions", p.claimedSessions()).add("claimedAmount", p.claimedAmount()).add("reason", p.reason()).build());
    }

    private void requireAgent() {
        if (!security.isCallerInRole("AGENT") && !security.isCallerInRole("ADMIN")) throw new ToolExecutionException("Only partner agents may use this tool");
    }

    private static LocalDate date(String text) {
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw new ToolExecutionException("'" + text + "' is not a date, use the format 2026-11-24");
        }
    }

    private static JsonObject json(PreauthAnswer a, String reference) {
        JsonArrayBuilder missing = Json.createArrayBuilder();
        a.missing().forEach(missing::add);
        JsonObjectBuilder b = Json.createObjectBuilder().add("status", a.status()).add("approvedSessions", a.approvedSessions()).add("approvedPerSession", a.approvedPerSession()).add("approvedTotal", a.approvedTotal()).add("reason", a.reason()).add("missing", missing).add("policyNumber", a.policyNumber() == null ? "" : a.policyNumber()).add("source", a.source());
        if (reference != null) b.add("reference", reference);
        return b.build();
    }
}
