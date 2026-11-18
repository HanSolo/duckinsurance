package eu.hansolo.duckinsurance.agent;

import eu.hansolo.mcp.client.McpClient;
import eu.hansolo.mcp.client.McpClientException;
import eu.hansolo.mcp.client.ToolResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


/**
 * The DuckClinic as seen by DuckInsurance: its MCP server (tools find_owners, list_vets, list_visits, book_visit), read through the MCP client library.
 * Only the read-only tools are used. The endpoint comes from DUCKCLINIC_MCP_URL; the default is the clinic on the host (Payara plugin, context root duck-clinic).
 */
@ApplicationScoped
public class ClinicClient {

    /** state: ok, warn or bad, like the indicator of the assistant page. */
    public record Status(String state, String text) {
    }

    public record ClinicDuck(long id, String name, String owner) {
    }


    public URI endpoint() {
        String url = System.getenv("DUCKCLINIC_MCP_URL");
        return URI.create(url == null || url.isBlank() ? "http://host.docker.internal:8080/duck-clinic/api/mcp" : url.trim());
    }

    /** Is the clinic reachable, and does it offer the tools the agent needs? */
    public Status check() {
        try {
            List<String> names = client().tools().stream().map(t -> t.getString("name", "")).toList();
            for (String needed : List.of("find_owners", "list_visits")) {
                if (!names.contains(needed)) return new Status("bad", "DuckClinic reachable, but the tool " + needed + " is missing");
            }
            return new Status("ok", "DuckClinic reachable at " + endpoint() + ", " + names.size() + " tools");
        } catch (RuntimeException e) {
            return new Status("bad", "DuckClinic not reachable at " + endpoint() + ": " + e.getMessage());
        }
    }

    /** Looks up a duck by the last name of its owner and its own name. */
    public Optional<ClinicDuck> findDuck(String ownerLastName, String duckName) {
        JsonObject result = call("find_owners", Json.createObjectBuilder().add("lastName", ownerLastName).build());
        JsonArray owners = result.getJsonArray("owners");
        if (owners == null) return Optional.empty();
        for (JsonValue o : owners) {
            JsonObject owner = o.asJsonObject();
            JsonArray ducks = owner.getJsonArray("ducks");
            if (ducks == null) continue;
            for (JsonValue d : ducks) {
                JsonObject duck = d.asJsonObject();
                if (duck.getString("name", "").equalsIgnoreCase(duckName)) return Optional.of(new ClinicDuck(duck.getJsonNumber("id").longValue(), duck.getString("name"), owner.getString("name", "")));
            }
        }
        return Optional.empty();
    }

    /** The visits of one duck as "date: description", newest first. */
    public List<String> visits(long duckId) {
        JsonObject result = call("list_visits", Json.createObjectBuilder().add("duckId", duckId).build());
        List<String> visits = new ArrayList<>();
        JsonArray array = result.getJsonArray("visits");
        if (array != null) for (JsonValue v : array) visits.add(v.asJsonObject().getString("date", "?") + ": " + v.asJsonObject().getString("description", ""));
        return visits;
    }

    private JsonObject call(String tool, JsonObject arguments) {
        try {
            ToolResult result = client().callTool(tool, arguments);
            if (result.isError() || result.structuredContent() == null) throw new IllegalStateException("The clinic tool " + tool + " returned no usable result");
            return result.structuredContent();
        } catch (McpClientException e) {
            throw new IllegalStateException("The clinic tool " + tool + " failed: " + e.getMessage(), e);
        }
    }

    private McpClient client() {
        return McpClient.builder(endpoint()).clientInfo("duckinsurance-fraud-agent", "1.0").timeout(Duration.ofSeconds(10)).build();
    }
}
