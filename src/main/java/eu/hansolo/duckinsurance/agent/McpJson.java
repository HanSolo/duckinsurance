package eu.hansolo.duckinsurance.agent;

import eu.hansolo.mcp.client.ToolResult;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.io.StringReader;

/** The data of a tool result. Servers send it as structured content, or only as JSON text in the first content block (Quarkus MCP server). */
final class McpJson {

    private McpJson() {
    }


    static JsonObject data(String tool, ToolResult result) {
        if (result.isError()) throw new ClinicUnavailableException("The tool " + tool + " reported an error: " + text(result), null);
        if (result.structuredContent() != null) return result.structuredContent();
        try {
            return Json.createReader(new StringReader(text(result))).readObject();
        } catch (RuntimeException e) {
            throw new ClinicUnavailableException("The tool " + tool + " returned no usable result", e);
        }
    }

    private static String text(ToolResult result) {
        for (JsonValue block : result.content()) {
            if (block instanceof JsonObject o && "text".equals(o.getString("type", ""))) return o.getString("text", "");
        }
        return "";
    }
}
