package eu.hansolo.duckinsurance.mcp;

import eu.hansolo.duckinsurance.agent.AgentCheck;
import eu.hansolo.duckinsurance.agent.AgentLog;
import eu.hansolo.duckinsurance.agent.PreauthAnswer;
import eu.hansolo.duckinsurance.agent.PreauthRequest;
import eu.hansolo.duckinsurance.agent.PreauthStore;
import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.server.McpRequestStates;
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
    private static final Logger LOG          = Logger.getLogger(PartnerTools.class.getName());
    private static final int    MAX_DOCUMENT = 8000;

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
    @Inject
    private McpRequestStates      states;


    @McpTool(name = "request_preauthorization", description = "A clinic asks whether DuckInsurance will pay for a treatment plan. The insurer's agent answers APPROVED, PARTIAL (fewer sessions or a lower amount per session) or DENIED. If it needs the veterinary referral document it asks for it inside the same call (MCP input_required, a form with the field referralDocument): do not send the document up front, answer the question. A client that cannot be asked gets NEEDS_INFO with a list of what is missing, and calls again with the argument referralDocument. Takes up to a minute because a language model decides.", destructiveHint = McpTool.Hint.FALSE)
    public ToolResult requestPreauthorization(@McpParam(description = "Referral id that all institutions carry, like REF-0001") String referralId, @McpParam(description = "Last name of the duck's owner") String ownerLastName, @McpParam(description = "Name of the duck") String duckName, @McpParam(description = "Name of the clinic that asks") String provider, @McpParam(description = "Name of the planned treatment") String treatmentName, @McpParam(description = "Diagnosis in one or two sentences") String diagnosis, @McpParam(description = "Number of planned sessions") int sessions, @McpParam(description = "Price per session in euro") double pricePerSession, @McpParam(description = "Date of the first session, 2026-12-01") String firstSession, @McpParam(description = "The veterinary referral document. Leave it out: the insurer asks for it if it needs it") Optional<String> referralDocument, ToolCall call) {
        try {
        requireAgent();
        if (sessions < 1 || pricePerSession <= 0) throw new ToolExecutionException("sessions and pricePerSession must be positive");
        // What the question is about: it is signed into the request state, so the retry cannot change the request after the insurer asked
        String arguments = String.join("|", referralId, ownerLastName, duckName, provider, treatmentName, diagnosis, String.valueOf(sessions), String.valueOf(pricePerSession), firstSession);
        String context   = "request_preauthorization|" + call.caller().subject();
        String document  = referralDocument.orElse(null);

        Optional<Elicitation.Answer> answer = Elicitation.answer(call.inputResponses(), DocumentForm.KEY);
        if (document == null && answer.isPresent()) {
            // -32602 if the state is missing, altered, expired or was issued for another tool or caller
            if (!arguments.equals(states.require(call.requestState(), context))) throw new ToolExecutionException("The request changed after the insurer asked for the referral document. Please ask again.");
            if (answer.get().accepted() && DocumentForm.form("Referral document").problems(answer.get()).isEmpty()) {
                document = DocumentForm.read(answer.get()).orElse(null);
                if (document != null && document.length() > MAX_DOCUMENT) throw new ToolExecutionException("The referral document is longer than " + MAX_DOCUMENT + " characters");
                log.say(referralId, null, "THOUGHT", "Received the referral document in answer to my question (" + (document == null ? 0 : document.length()) + " characters).");
            } else {
                log.say(referralId, provider, "ANSWER", "You declined to send the referral document, so I cannot decide.");
            }
        }

        String correlation = UUID.randomUUID().toString();
        PreauthRequest request = new PreauthRequest(correlation, referralId, ownerLastName, duckName, provider, treatmentName, diagnosis, sessions, BigDecimal.valueOf(pricePerSession), date(firstSession), document);
        try {
            requests.fire(request);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Pre-authorization workflow failed", e);
            throw new ToolExecutionException("The insurer's agent failed: " + e.getMessage());
        }
        PreauthAnswer result = store.takeAnswer(correlation);
        if (result == null || result.status().equals("ERROR")) throw new ToolExecutionException("The insurer's agent gave no answer");

        boolean missingDocument = result.status().equals("NEEDS_INFO") && result.missing().contains(DocumentForm.KEY);
        if (missingDocument && answer.isEmpty() && call.clientSupports("elicitation")) {
            // Ask inside this call instead of ending it: the caller answers and repeats the call with the answer and the signed state
            log.say(referralId, provider, "REQUEST", "Please send me the referral document. I ask inside this call (MCP input_required), you do not have to start over.");
            return ToolResult.ask(InputRequests.of(DocumentForm.KEY, DocumentForm.form(result.reason())), states.sign(arguments, context));
        }
        PreauthStore.Preauth saved = store.get(referralId);
        return ToolResult.structured(json(result, saved == null || result.status().equals("NEEDS_INFO") ? null : saved.reference()));
        } catch (ToolExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            // Anything else would reach the caller only as "Tool execution failed": say what it was, in the log and in the answer
            LOG.log(Level.WARNING, "request_preauthorization failed unexpectedly", e);
            Throwable root = e; while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            throw new ToolExecutionException("Unexpected error in request_preauthorization: " + e.getClass().getSimpleName() + ": " + e.getMessage() + (root == e ? "" : " (cause: " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")"));
        }
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

    @McpTool(name = "release_preauthorization", description = "A clinic cancels a treatment plan that was pre-authorized. The insurer cancels the pre-authorization: sessions that were claimed stay claimed, the coverage reserved for the sessions not yet claimed is released.")
    public ToolResult releasePreauthorization(@McpParam(description = "Referral id of the pre-authorization, like REF-0001") String referralId, @McpParam(description = "Why the plan is cancelled") Optional<String> reason) {
        requireAgent();
        PreauthStore.Preauth before = store.get(referralId);
        if (before == null) throw new ToolExecutionException("There is no pre-authorization for " + referralId);
        if (before.status().equals("CANCELLED")) throw new ToolExecutionException("The pre-authorization " + before.reference() + " was cancelled already");
        if (!(before.status().equals("APPROVED") || before.status().equals("PARTIAL"))) throw new ToolExecutionException("Nothing to release: the pre-authorization " + before.reference() + " is " + before.status());
        String     why      = reason.filter(r -> !r.isBlank()).map(String::strip).orElse("no reason given");
        int        sessions = before.approvedSessions() - before.claimedSessions();
        BigDecimal amount   = before.reservation();
        store.cancel(referralId, why);
        log.say(referralId, null, "POLICY", "Rule: claimed sessions stay claimed, the coverage reserved for sessions that were not claimed is released. " + before.claimedSessions() + " of " + before.approvedSessions() + " session(s) were claimed.");
        log.say(referralId, "Therapy Agent", "ANSWER", "Pre-authorization " + before.reference() + " cancelled (" + why + "). Released " + sessions + " session(s), " + amount + " EUR of coverage. " + before.claimedSessions() + " claimed session(s) stay.");
        return ToolResult.structured(Json.createObjectBuilder().add("reference", before.reference()).add("status", "CANCELLED").add("releasedSessions", sessions).add("releasedAmount", amount).add("claimedSessions", before.claimedSessions()).add("claimedAmount", before.claimedAmount()).build());
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
