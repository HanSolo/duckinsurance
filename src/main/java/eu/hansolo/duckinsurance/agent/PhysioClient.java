package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.config.AppConfig;
import eu.hansolo.mcp.client.McpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


/**
 * The Duck Physical Therapy Clinic as seen by DuckInsurance: its MCP server (a Quarkus application), read and, on request of an adjuster, written through the MCP client library.
 * Tools used: list_ducks, list_appointments_for_duck, list_treatments, list_therapists and book_appointment. The endpoint comes from PHYSIOCLINIC_MCP_URL.
 */
@ApplicationScoped
public class PhysioClient {

    public record PhysioDuck(long id, String name, String owner) {
    }

    /** A treatment or a therapist: id and a line of text. */
    public record Option(long id, String name, String text) {
    }

    public record Appointment(LocalDate date, String text) {
    }


    public URI endpoint() {
        return URI.create(AppConfig.get("PHYSIOCLINIC_MCP_URL", "http://host.docker.internal:8082/mcp"));
    }

    /** Is the clinic reachable, and does it offer the tools the agent needs? */
    public ClinicClient.Status check() {
        try {
            List<String> names = client().tools().stream().map(t -> t.getString("name", "")).toList();
            for (String needed : List.of("list_ducks", "list_appointments_for_duck", "book_appointment")) {
                if (!names.contains(needed)) return new ClinicClient.Status("bad", "Physical therapy clinic reachable, but the tool " + needed + " is missing");
            }
            return new ClinicClient.Status("ok", "Physical therapy clinic reachable at " + endpoint() + ", " + names.size() + " tools");
        } catch (RuntimeException e) {
            return new ClinicClient.Status("bad", "Physical therapy clinic not reachable at " + endpoint() + ": " + e.getMessage());
        }
    }

    /** Looks up a duck by the last name of its owner and its own name. */
    public Optional<PhysioDuck> findDuck(String ownerLastName, String duckName) {
        JsonArray ducks = call("list_ducks", Json.createObjectBuilder().build()).getJsonArray("ducks");
        if (ducks == null) return Optional.empty();
        for (JsonValue d : ducks) {
            JsonObject duck = d.asJsonObject();
            if (duck.getString("name", "").equalsIgnoreCase(duckName) && duck.getString("ownerName", "").toLowerCase().contains(ownerLastName.toLowerCase())) {
                return Optional.of(new PhysioDuck(duck.getJsonNumber("id").longValue(), duck.getString("name"), duck.getString("ownerName", "")));
            }
        }
        return Optional.empty();
    }

    /** The appointments of one duck that are not cancelled, oldest first. */
    public List<Appointment> appointments(long duckId) {
        JsonArray array = call("list_appointments_for_duck", Json.createObjectBuilder().add("duckId", duckId).build()).getJsonArray("appointments");
        List<Appointment> result = new ArrayList<>();
        if (array == null) return result;
        for (JsonValue v : array) {
            JsonObject a = v.asJsonObject();
            LocalDate date = LocalDateTime.parse(a.getString("startTime")).toLocalDate();
            result.add(new Appointment(date, date + ": " + a.getString("treatmentName", "?") + " with " + a.getString("therapistName", "?") + " (" + a.getString("status", "?") + ")"));
        }
        return result;
    }

    public List<Option> treatments() {
        List<Option> result = new ArrayList<>();
        JsonArray array = call("list_treatments", Json.createObjectBuilder().build()).getJsonArray("treatments");
        if (array != null) for (JsonValue v : array) {
            JsonObject t = v.asJsonObject();
            result.add(new Option(t.getJsonNumber("id").longValue(), t.getString("name"), t.getString("name") + ", " + t.getInt("durationMinutes") + " minutes, " + t.get("price") + " EUR"));
        }
        return result;
    }

    public List<Option> therapists() {
        List<Option> result = new ArrayList<>();
        JsonArray array = call("list_therapists", Json.createObjectBuilder().build()).getJsonArray("therapists");
        if (array != null) for (JsonValue v : array) {
            JsonObject t = v.asJsonObject();
            result.add(new Option(t.getJsonNumber("id").longValue(), t.getString("name"), t.getString("name") + " (" + t.getString("specialty", "") + ")"));
        }
        return result;
    }

    /** Books an appointment; this changes data in the other application. @return the appointment number, for example APT-00001 */
    public String book(long duckId, long treatmentId, long therapistId, LocalDateTime start, String notes) {
        JsonObject arguments = Json.createObjectBuilder().add("duckId", duckId).add("treatmentId", treatmentId).add("therapistId", therapistId).add("startTime", start.withSecond(0).withNano(0).toString()).add("notes", notes).build();
        try {
            return McpJson.data("book_appointment", client().callTool("book_appointment", arguments)).getString("appointmentNumber", "?");
        } catch (ClinicUnavailableException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private JsonObject call(String tool, JsonObject arguments) {
        try {
            return McpJson.data(tool, client().callTool(tool, arguments));
        } catch (ClinicUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ClinicUnavailableException("The physical therapy clinic tool " + tool + " failed: " + e.getMessage(), e);
        }
    }

    private McpClient client() {
        return McpClient.builder(endpoint()).clientInfo("duckinsurance-fraud-agent", "1.0").timeout(Duration.ofSeconds(10)).build();
    }
}
