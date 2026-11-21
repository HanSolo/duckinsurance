package eu.hansolo.duckinsurance.rest;

import eu.hansolo.duckinsurance.agent.AgentLog;
import eu.hansolo.duckinsurance.agent.PartnerDirectory;
import eu.hansolo.duckinsurance.agent.PreauthStore;
import jakarta.annotation.security.RolesAllowed;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


/**
 * Back end of the conversation page (Agents). It merges the agent logs of the three applications into one timeline and starts the demo: a referral at the DuckClinic, and the completion of a session at the physical therapy clinic.
 * The browser only talks to DuckInsurance; DuckInsurance calls the other two applications.
 */
@Path("/agent")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"ADMIN", "ADJUSTER"})
@RequestScoped
public class AgentResource {
    @Inject
    private AgentLog         log;
    @Inject
    private PreauthStore     store;
    @Inject
    private PartnerDirectory partners;


    /** The conversation of all three agents, oldest line first. "reachable" says which applications answered. */
    @GET
    @Path("/timeline")
    public JsonObject timeline() {
        List<JsonObject> lines = new ArrayList<>();
        add(lines, log.toJson(), "DuckInsurance");
        String clinic = partners.get(partners.clinicRest() + "/agent/log");
        String physio = partners.get(partners.physioRest() + "/agent/log");
        if (clinic != null) add(lines, array(clinic), "DuckClinic");
        if (physio != null) add(lines, array(physio), "PhysicalTherapy");
        lines.sort(Comparator.<JsonObject>comparingLong(o -> o.getJsonNumber("at").longValue()).thenComparing(o -> o.getString("app")).thenComparingLong(o -> o.getJsonNumber("seq").longValue()));
        JsonArrayBuilder entries = Json.createArrayBuilder();
        lines.forEach(entries::add);
        JsonArrayBuilder preauths = Json.createArrayBuilder();
        store.all().forEach(p -> preauths.add(Json.createObjectBuilder().add("reference", p.reference()).add("referralId", p.referralId()).add("status", p.status()).add("approvedSessions", p.approvedSessions()).add("claimedSessions", p.claimedSessions()).add("approvedTotal", p.approvedTotal())));
        String referrals = clinic == null ? null : partners.get(partners.clinicRest() + "/referrals");
        return Json.createObjectBuilder().add("entries", entries).add("preauthorizations", preauths).add("referrals", referrals == null ? Json.createArrayBuilder().build() : array(referrals)).add("clinicReachable", clinic != null).add("physioReachable", physio != null).build();
    }

    /** Starts the demo: the DuckClinic's agent looks at the duck and decides on a referral. Returns at once, the conversation appears on the timeline. */
    @POST
    @Path("/referrals")
    public Response startReferral(@QueryParam("owner") String owner, @QueryParam("duck") String duck) {
        String json = Json.createObjectBuilder().add("ownerLastName", owner == null || owner.isBlank() ? "Keller" : owner).add("duckName", duck == null || duck.isBlank() ? "Sir Quacksalot" : duck).add("ownerConsent", true).build().toString();
        return relay(partners.post(partners.clinicRest() + "/referrals", json), "The DuckClinic is not reachable at " + partners.clinicRest());
    }

    /** The physical therapy clinic completes the next booked session of a referral; its agent then bills the insurer. */
    @POST
    @Path("/complete-session")
    public Response completeSession(@QueryParam("referralId") String referralId) {
        String url = partners.physioRest() + "/agent/complete-next?referralId=" + URLEncoder.encode(referralId == null ? "" : referralId, StandardCharsets.UTF_8);
        return relay(partners.post(url, ""), "The Duck Physical Therapy Clinic is not reachable at " + partners.physioRest());
    }

    /** The vet cancels a scheduled referral: the DuckClinic's agent asks the therapy clinic's agent, which cancels the booked sessions and has the insurer release the reserved coverage. */
    @POST
    @Path("/cancel")
    public Response cancelReferral(@QueryParam("referralId") String referralId, @QueryParam("reason") String reason) {
        String url = partners.clinicRest() + "/referrals/" + URLEncoder.encode(referralId == null ? "" : referralId, StandardCharsets.UTF_8) + "/cancel" + (reason == null || reason.isBlank() ? "" : "?reason=" + URLEncoder.encode(reason, StandardCharsets.UTF_8));
        return relay(partners.post(url, ""), "The DuckClinic is not reachable at " + partners.clinicRest());
    }

    /** Forgets the conversation and the pre-authorizations here and in the two clinics, so that the demo can be shown again. The data (visits, appointments, claims) stays. */
    @DELETE
    @Path("/log")
    public Response reset() {
        log.clear();
        store.clear();
        partners.delete(partners.clinicRest() + "/agent/log");
        partners.delete(partners.physioRest() + "/agent/log");
        return Response.noContent().build();
    }

    private static Response relay(String body, String unreachable) {
        if (body == null) return Response.status(Response.Status.BAD_GATEWAY).entity(Json.createObjectBuilder().add("error", unreachable).build()).build();
        return Response.ok(body).build();
    }

    private static JsonArray array(String json) {
        try (var reader = Json.createReader(new StringReader(json))) {
            JsonValue value = reader.readValue();
            if (value instanceof JsonArray a) return a;
            if (value instanceof JsonObject o && o.get("entries") instanceof JsonArray a) return a;
            return Json.createArrayBuilder().build();
        } catch (RuntimeException e) {
            return Json.createArrayBuilder().build();
        }
    }

    private static void add(List<JsonObject> lines, JsonArray entries, String app) {
        for (JsonValue v : entries) lines.add(Json.createObjectBuilder(v.asJsonObject()).add("app", app).build());
    }
}
