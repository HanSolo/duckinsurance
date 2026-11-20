package eu.hansolo.duckinsurance.mcp;

import eu.hansolo.duckinsurance.config.AppConfig;
import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.ClaimStatus;
import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.duckinsurance.domain.Duck;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.domain.PolicyType;
import eu.hansolo.duckinsurance.domain.Vet;
import eu.hansolo.duckinsurance.service.ClaimService;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.DuckService;
import eu.hansolo.duckinsurance.service.PolicyService;
import eu.hansolo.duckinsurance.service.StatsService;
import eu.hansolo.duckinsurance.service.VetService;
import eu.hansolo.mcp.api.Elicitation;
import eu.hansolo.mcp.api.InputRequests;
import eu.hansolo.mcp.api.McpParam;
import eu.hansolo.mcp.api.McpTool;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExecutionException;
import eu.hansolo.mcp.api.ToolResult;
import eu.hansolo.mcp.protocol.McpProtocolException;
import eu.hansolo.mcp.server.McpRequestStates;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The DuckInsurance services as MCP tools. The tools call the same CDI services as the REST resources and the Faces beans, in the same process.
 * The endpoint (/api/mcp) is protected by the application's own login, so roles still apply: only an adjuster or admin can decide a claim.
 */
@ApplicationScoped
public class DuckTools {

    public record OwnerView(long id, String name, String email, String city, int age) {
    }

    public record Owners(List<OwnerView> owners) {
    }

    public record DuckView(long id, String name, String breed, String age, long ownerId) {
    }

    public record Ducks(List<DuckView> ducks) {
    }

    public record VetView(long id, String name, String specialty, String city) {
    }

    public record Vets(List<VetView> vets) {
    }

    public record PolicyView(long id, String number, long duckId, long ownerId, String type, double coverage, double annualPremium, String start, String end, String status) {
    }

    public record Policies(List<PolicyView> policies) {
    }

    public record ClaimView(long id, String number, long policyId, Optional<Long> vetId, String description, double amount, String incidentDate, String status, Optional<Integer> fraudScore, Optional<String> decisionNote) {
    }

    public record Claims(List<ClaimView> claims) {
    }

    public record QuoteView(long duckId, String type, double coverage, double annualPremium) {
    }

    public record StatsView(long owners, long ducks, long activePolicies, double annualPremium, long openClaims) {
    }

    @Inject
    private CustomerService customers;
    @Inject
    private DuckService     ducks;
    @Inject
    private VetService      vets;
    @Inject
    private PolicyService   policies;
    @Inject
    private ClaimService    claims;
    @Inject
    private StatsService    stats;
    @Inject
    private McpRequestStates states;


    @McpTool(name = "search_owners", description = "Searches duck owners by name or e-mail. Without a query it lists all owners.", readOnlyHint = McpTool.Hint.TRUE)
    public Owners searchOwners(@McpParam(description = "Part of a name or e-mail address") Optional<String> query) {
        return run(() -> new Owners(customers.list(query.orElse(null)).stream().map(DuckTools::view).toList()));
    }

    @McpTool(name = "list_ducks", description = "Lists ducks with breed, age and owner. Optionally only the ducks of one owner.", readOnlyHint = McpTool.Hint.TRUE)
    public Ducks listDucks(@McpParam(description = "Owner id, see search_owners") Optional<Long> ownerId) {
        return run(() -> new Ducks(ducks.list(ownerId.orElse(null)).stream().map(DuckTools::view).toList()));
    }

    @McpTool(name = "list_vets", description = "Lists the duck vets with specialty and city.", readOnlyHint = McpTool.Hint.TRUE)
    public Vets listVets() {
        return run(() -> new Vets(vets.list().stream().map(DuckTools::view).toList()));
    }

    @McpTool(name = "list_policies", description = "Lists insurance policies. Optionally only the policies of one duck.", readOnlyHint = McpTool.Hint.TRUE)
    public Policies listPolicies(@McpParam(description = "Duck id, see list_ducks") Optional<Long> duckId) {
        return run(() -> new Policies(policies.list().stream().filter(p -> duckId.isEmpty() || duckId.get().equals(p.getDuckId())).map(DuckTools::view).toList()));
    }

    @McpTool(name = "quote_policy", description = "Calculates the yearly premium for a policy without issuing it. The premium depends on the policy type, the coverage, the breed and the age of the duck.", readOnlyHint = McpTool.Hint.TRUE)
    public QuoteView quotePolicy(@McpParam(description = "Duck id") long duckId, @McpParam(description = "Policy type: VET_CARE, LIFE, LIABILITY or COOP") PolicyType type, @McpParam(description = "Coverage amount in euro") double coverage) {
        return run(() -> new QuoteView(duckId, type.name(), coverage, policies.quote(duckId, type, BigDecimal.valueOf(coverage)).doubleValue()));
    }

    @McpTool(name = "issue_policy", description = "Issues a new one-year policy for a duck. Owners under 18 cannot hold a policy. Changes data: the user is asked to confirm first, only call it when the user asked for it.", destructiveHint = McpTool.Hint.FALSE)
    public ToolResult issuePolicy(@McpParam(description = "Duck id") long duckId, @McpParam(description = "Policy type: VET_CARE, LIFE, LIABILITY or COOP") PolicyType type, @McpParam(description = "Coverage amount in euro") double coverage, @McpParam(description = "Start date, ISO format 2026-11-24. Default is today") Optional<String> startDate, ToolCall call) {
        LocalDate start = date(startDate.orElse(null), LocalDate.now());
        ToolResult ask = confirm(call, "issue_policy", duckId + "|" + type + "|" + coverage + "|" + start, "Issue a " + type + " policy with " + (long) coverage + " euro coverage for duck " + duckId + "?");
        if (ask != null) return ask;
        return run(() -> done(json(view(policies.issue(duckId, type, BigDecimal.valueOf(coverage), start)))));
    }

    @McpTool(name = "list_claims", description = "Lists insurance claims, newest first. Optionally only claims with one status.", readOnlyHint = McpTool.Hint.TRUE)
    public Claims listClaims(@McpParam(description = "SUBMITTED, UNDER_REVIEW, APPROVED or REJECTED") Optional<ClaimStatus> status) {
        return run(() -> new Claims(claims.list(status.orElse(null)).stream().map(DuckTools::view).toList()));
    }

    @McpTool(name = "submit_claim", description = "Files a claim against an active policy. A fraud check runs afterwards and moves the claim to UNDER_REVIEW. Changes data: the user is asked to confirm first, only call it when the user asked for it.", destructiveHint = McpTool.Hint.FALSE)
    public ToolResult submitClaim(@McpParam(description = "Policy id, see list_policies") long policyId, @McpParam(description = "What happened, at least 10 characters") String description, @McpParam(description = "Claimed amount in euro, not above the coverage") double amount, @McpParam(description = "Date of the incident, ISO format 2026-11-24, within the policy period") String incidentDate, @McpParam(description = "Id of the vet who treated the duck, see list_vets") Optional<Long> vetId, ToolCall call) {
        if (description == null || description.strip().length() < 10) throw new ToolExecutionException("The description needs at least 10 characters");
        LocalDate incident = date(incidentDate, null);
        // The free text is part of what is confirmed (it is in the signed state) but not of the message: texts that look like secrets are refused in elicitation messages
        ToolResult ask = confirm(call, "submit_claim", policyId + "|" + description.strip() + "|" + amount + "|" + incident + "|" + vetId.orElse(null), "File a claim of " + (long) amount + " euro against policy " + policyId + "?");
        if (ask != null) return ask;
        return run(() -> done(json(view(claims.submit(policyId, description.strip(), BigDecimal.valueOf(amount), incident, vetId.orElse(null))))));
    }

    @McpTool(name = "decide_claim", description = "Approves or rejects an open claim. Only adjusters and admins may do this. Changes data: the user is asked to confirm first, only call it when the user asked for it.", destructiveHint = McpTool.Hint.TRUE)
    public ToolResult decideClaim(@McpParam(description = "Claim id") long claimId, @McpParam(description = "true to approve, false to reject") boolean approve, @McpParam(description = "Reason for the decision") Optional<String> note, ToolCall call) {
        ToolResult ask = confirm(call, "decide_claim", claimId + "|" + approve + "|" + note.orElse(""), (approve ? "Approve" : "Reject") + " claim " + claimId + "?");
        if (ask != null) return ask;
        return run(() -> done(json(view(claims.decide(claimId, approve, note.orElse(null))))));
    }

    @McpTool(name = "get_stats", description = "Shows the business numbers: owners, ducks, active policies, yearly premium volume and open claims.", readOnlyHint = McpTool.Hint.TRUE)
    public StatsView getStats() {
        return run(() -> {
            StatsService.Stats s = stats.current();
            return new StatsView(s.owners(), s.ducks(), s.activePolicies(), s.annualPremium().doubleValue(), s.openClaims());
        });
    }

    /**
     * The confirmation step of a data-changing tool (MCP elicitation, multi round-trip): the first call returns a question for the user together with a signed state
     * that holds the exact arguments. The client asks the user and repeats the call with the answer; only if the state matches the arguments and the user accepted does the tool act.
     * DUCK_CONFIRM_WRITES: "if-supported" (default) asks when the client can ask and acts directly otherwise, "always" refuses clients that cannot ask, "off" never asks.
     *
     * @return null if the tool may go ahead, otherwise the result to return now
     */
    private ToolResult confirm(ToolCall call, String tool, String arguments, String message) {
        String mode = AppConfig.get("DUCK_CONFIRM_WRITES", "if-supported");
        if (mode.equals("off")) return null;
        String context = tool + "|" + call.caller().subject();
        Optional<Elicitation.Answer> answer = Elicitation.answer(call.inputResponses(), "confirm");
        if (answer.isPresent()) {
            // -32602 if the state is missing, altered, expired or was issued for another tool or caller
            String confirmed = states.require(call.requestState(), context);
            if (confirmed.equals(arguments)) {
                return answer.get().accepted() ? null : ToolResult.error("Nothing was changed: the user declined.");
            }
            // the user confirmed something else than this call asks for: ask again
        }
        if (!call.clientSupports("elicitation")) {
            if (mode.equals("always")) throw McpProtocolException.missingClientCapability("elicitation");
            return null;
        }
        return ToolResult.ask(InputRequests.of("confirm", Elicitation.form(message)), states.sign(arguments, context));
    }

    private static ToolResult done(JsonObject data) {
        return ToolResult.structured(data);
    }

    private static JsonObject json(PolicyView p) {
        return Json.createObjectBuilder().add("id", p.id()).add("number", p.number()).add("duckId", p.duckId()).add("ownerId", p.ownerId()).add("type", p.type()).add("coverage", p.coverage()).add("annualPremium", p.annualPremium()).add("start", p.start()).add("end", p.end()).add("status", p.status()).build();
    }

    private static JsonObject json(ClaimView c) {
        JsonObjectBuilder b = Json.createObjectBuilder().add("id", c.id()).add("number", c.number()).add("policyId", c.policyId()).add("description", c.description()).add("amount", c.amount()).add("incidentDate", c.incidentDate()).add("status", c.status());
        c.vetId().ifPresent(v -> b.add("vetId", v));
        c.fraudScore().ifPresent(f -> b.add("fraudScore", f));
        c.decisionNote().ifPresent(n -> b.add("decisionNote", n));
        return b.build();
    }

    /** Turns business errors into messages the model can read and react to. */
    private static <T> T run(Supplier<T> action) {
        try {
            return action.get();
        } catch (DomainException e) {
            throw new ToolExecutionException(e.getMessage());
        }
    }

    private static LocalDate date(String text, LocalDate fallback) {
        if (text == null || text.isBlank()) {
            if (fallback == null) throw new ToolExecutionException("A date is required, format 2026-11-24");
            return fallback;
        }
        try {
            return LocalDate.parse(text.trim());
        } catch (DateTimeParseException e) {
            throw new ToolExecutionException("'" + text + "' is not a date, use the format 2026-11-24");
        }
    }

    private static OwnerView view(Customer c) {
        return new OwnerView(c.getId(), c.getFullName(), c.getEmail(), c.getCity(), c.getAge());
    }

    private static DuckView view(Duck d) {
        return new DuckView(d.getId(), d.getName(), d.getBreed().getLabel(), d.getAgeLabel(), d.getOwnerId());
    }

    private static VetView view(Vet v) {
        return new VetView(v.getId(), v.getName(), v.getSpecialty(), v.getCity());
    }

    private static PolicyView view(Policy p) {
        return new PolicyView(p.getId(), p.getPolicyNumber(), p.getDuckId(), p.getCustomerId(), p.getType().name(), p.getCoverageAmount().doubleValue(), p.getAnnualPremium().doubleValue(), p.getStartDate().toString(), p.getEndDate().toString(), p.getStatus().name());
    }

    private static ClaimView view(Claim c) {
        return new ClaimView(c.getId(), c.getClaimNumber(), c.getPolicyId(), Optional.ofNullable(c.getVetId()), c.getDescription(), c.getAmount().doubleValue(), c.getIncidentDate().toString(), c.getStatus().name(), Optional.ofNullable(c.getFraudScore()), Optional.ofNullable(c.getDecisionNote()));
    }
}
