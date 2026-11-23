package eu.hansolo.duckinsurance.chat;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;


/** The Anthropic Messages API over {@link HttpClient} and JSON-P, without a vendor SDK, with and without streaming. Any server that speaks the same API works, for example a local Ollama. */
public final class AnthropicLlm implements Llm {
    static final String SYSTEM = "You are the assistant of DuckInsurance, an insurer for ducks, their owners and vets. Use the tools to answer. Look up ids with the list and search tools instead of guessing them. Only change data (issue a policy, submit or decide a claim) when the user clearly asks for it; the user is asked to confirm each change. If a tool reports an error or the user declined, say so plainly. Answer briefly.";
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI        endpoint;
    private final String     apiKey;
    private final String     model;
    private final int        maxTokens;


    /** @param baseUrl the server without the path, for example {@code https://api.anthropic.com} or {@code http://localhost:11434} */
    public AnthropicLlm(final String baseUrl, final String apiKey, final String model, final int maxTokens) {
        this.endpoint  = URI.create((baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl) + "/v1/messages");
        this.apiKey    = apiKey;
        this.model     = model;
        this.maxTokens = maxTokens;
    }


    @Override public JsonArray complete(final JsonArray tools, final JsonArray messages) {
        HttpRequest request = request(tools, messages, false, Duration.ofSeconds(180));
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { throw new IllegalStateException("The model server answered with status " + response.statusCode() + ": " + response.body()); }
            return clean(Json.createReader(new StringReader(response.body())).readObject().getJsonArray("content"));
        } catch (IOException e) {
            throw new IllegalStateException("The model server could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model server", e);
        }
    }

    /** Streaming: server-sent events. Text deltas go to {@code onText} as they arrive; tool calls are assembled from their JSON fragments. */
    @Override public JsonArray complete(final JsonArray tools, final JsonArray messages, final Consumer<String> onText) {
        HttpRequest request = request(tools, messages, true, Duration.ofSeconds(600));
        Map<Integer, Block> blocks = new TreeMap<>();
        try {
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String body = response.body().reduce("", (a, b) -> a + b);
                throw new IllegalStateException("The model server answered with status " + response.statusCode() + ": " + body);
            }
            try (Stream<String> lines = response.body()) {
                for (var iterator = lines.iterator(); iterator.hasNext(); ) {
                    String line = iterator.next();
                    if (!line.startsWith("data:")) { continue; }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) { continue; }
                    handle(Json.createReader(new StringReader(data)).readObject(), blocks, onText);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The model server could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model server", e);
        }
        JsonArrayBuilder result = Json.createArrayBuilder();
        for (Block block : blocks.values()) {
            if (block.type.equals("text") && !block.text.isEmpty()) {
                result.add(Json.createObjectBuilder().add("type", "text").add("text", block.text.toString()));
            } else if (block.type.equals("tool_use")) {
                result.add(Json.createObjectBuilder().add("type", "tool_use").add("id", block.id).add("name", block.name).add("input", block.input()));
            }
        }
        return result.build();
    }

    private void handle(final JsonObject event, final Map<Integer, Block> blocks, final Consumer<String> onText) {
        switch (event.getString("type", "")) {
            case "content_block_start" -> {
                JsonObject start = event.getJsonObject("content_block");
                Block      block = new Block(start.getString("type", ""), start.getString("id", ""), start.getString("name", ""));
                if (start.containsKey("input") && start.get("input").getValueType() == JsonValue.ValueType.OBJECT) { block.startInput = start.getJsonObject("input"); }
                if (block.type.equals("text") && !start.getString("text", "").isEmpty()) { block.text.append(start.getString("text")); onText.accept(start.getString("text")); }
                blocks.put(event.getInt("index"), block);
            }
            case "content_block_delta" -> {
                Block      block = blocks.get(event.getInt("index"));
                JsonObject delta = event.getJsonObject("delta");
                if (block == null) { return; }
                switch (delta.getString("type", "")) {
                    case "text_delta"       -> { String text = delta.getString("text", ""); block.text.append(text); onText.accept(text); }
                    case "input_json_delta" -> block.json.append(delta.getString("partial_json", ""));
                    default                 -> { }
                }
            }
            case "error" -> throw new IllegalStateException("The model server reported an error: " + event.getJsonObject("error").getString("message", event.toString()));
            default -> { }
        }
    }

    private HttpRequest request(final JsonArray tools, final JsonArray messages, final boolean stream, final Duration timeout) {
        var body = Json.createObjectBuilder().add("model", model).add("max_tokens", maxTokens).add("system", SYSTEM).add("messages", messages);
        if (stream) { body.add("stream", true); }
        if (!tools.isEmpty()) { body.add("tools", tools); }
        return HttpRequest.newBuilder(endpoint).timeout(timeout).header("content-type", "application/json").header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").POST(HttpRequest.BodyPublishers.ofString(body.build().toString())).build();
    }

    /** Keeps only what is sent back to the API in the next request: text and tool_use blocks, without any extra fields. */
    private static JsonArray clean(final JsonArray content) {
        JsonArrayBuilder blocks = Json.createArrayBuilder();
        for (JsonValue value : content) {
            JsonObject block = value.asJsonObject();
            switch (block.getString("type", "")) {
                case "text"     -> blocks.add(Json.createObjectBuilder().add("type", "text").add("text", block.getString("text", "")));
                case "tool_use" -> blocks.add(Json.createObjectBuilder().add("type", "tool_use").add("id", block.getString("id")).add("name", block.getString("name")).add("input", block.getJsonObject("input")));
                default         -> { }
            }
        }
        return blocks.build();
    }


    /** A content block while it is being streamed. */
    private static final class Block {
        private final String        type;
        private final String        id;
        private final String        name;
        private final StringBuilder text  = new StringBuilder();
        private final StringBuilder json  = new StringBuilder();
        private       JsonObject    startInput;


        private Block(final String type, final String id, final String name) {
            this.type = type;
            this.id   = id;
            this.name = name;
        }


        private JsonObject input() {
            if (json.toString().isBlank()) { return startInput == null ? Json.createObjectBuilder().build() : startInput; }
            return Json.createReader(new StringReader(json.toString())).readObject();
        }
    }
}
