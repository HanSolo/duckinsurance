package eu.hansolo.duckinsurance.chat;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
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


/**
 * The OpenAI Chat Completions API ({base}/chat/completions) over {@link HttpClient} and JSON-P, with and without streaming, for OpenAI, Groq, OpenRouter, Mistral,
 * Ollama's /v1 and other services in that format. The rest of the Assistant works with the shape of the Anthropic Messages API ({@link Llm}), so this class
 * translates in both directions:
 * <ul>
 *   <li>tools: {@code name}, {@code description}, {@code input_schema} become {@code {"type":"function","function":{name, description, parameters}}}</li>
 *   <li>assistant {@code tool_use} blocks become {@code tool_calls} (arguments as a JSON string), user {@code tool_result} blocks become {@code role: tool} messages</li>
 *   <li>the reply ({@code content} and {@code tool_calls}) becomes {@code text} and {@code tool_use} blocks again</li>
 * </ul>
 */
public final class OpenAiLlm implements Llm {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final URI        endpoint;
    private final String     apiKey;
    private final String     model;
    private final Integer    maxTokens;


    /**
     * @param baseUrl   the server including the version path, for example {@code https://api.openai.com/v1}
     * @param maxTokens the answer limit, or {@code null} to send none (newer OpenAI models reject {@code max_tokens})
     */
    public OpenAiLlm(final String baseUrl, final String apiKey, final String model, final Integer maxTokens) {
        this.endpoint  = URI.create(baseUrl.replaceAll("/+$", "") + "/chat/completions");
        this.apiKey    = apiKey;
        this.model     = model;
        this.maxTokens = maxTokens;
    }


    @Override public JsonArray complete(final JsonArray tools, final JsonArray messages) {
        HttpRequest request = request(tools, messages, false, Duration.ofSeconds(180));
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { throw new IllegalStateException("The model server answered with status " + response.statusCode() + ": " + response.body()); }
            JsonObject body    = Json.createReader(new StringReader(response.body())).readObject();
            JsonArray  choices = body.getJsonArray("choices");
            if (choices == null || choices.isEmpty()) { throw new IllegalStateException("The model server sent no choices: " + body); }
            JsonObject message = choices.getJsonObject(0).getJsonObject("message");
            JsonArrayBuilder blocks = Json.createArrayBuilder();
            String text = message.containsKey("content") && !message.isNull("content") ? message.getString("content", "") : "";
            if (!text.isEmpty()) { blocks.add(Json.createObjectBuilder().add("type", "text").add("text", text)); }
            if (message.containsKey("tool_calls") && !message.isNull("tool_calls")) {
                int index = 0;
                for (JsonValue value : message.getJsonArray("tool_calls")) {
                    JsonObject call     = value.asJsonObject();
                    JsonObject function = call.getJsonObject("function");
                    blocks.add(toolUse(call.getString("id", "call_" + index), function.getString("name", ""), arguments(function)));
                    index++;
                }
            }
            return blocks.build();
        } catch (IOException e) {
            throw new IllegalStateException("The model server could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model server", e);
        }
    }

    /** Streaming: server-sent events. Text deltas go to {@code onText} as they arrive; tool calls are assembled from their fragments by index. */
    @Override public JsonArray complete(final JsonArray tools, final JsonArray messages, final Consumer<String> onText) {
        HttpRequest          request = request(tools, messages, true, Duration.ofSeconds(600));
        StringBuilder        text    = new StringBuilder();
        Map<Integer, Call>   calls   = new TreeMap<>();
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
                    JsonObject event = Json.createReader(new StringReader(data)).readObject();
                    if (event.containsKey("error") && !event.isNull("error")) { throw new IllegalStateException("The model server reported an error: " + event.get("error")); }
                    JsonArray choices = event.getJsonArray("choices");
                    if (choices == null || choices.isEmpty()) { continue; }
                    JsonObject delta = choices.getJsonObject(0).getJsonObject("delta");
                    if (delta == null) { continue; }
                    if (delta.containsKey("content") && !delta.isNull("content")) {
                        String piece = delta.getString("content", "");
                        if (!piece.isEmpty()) { text.append(piece); onText.accept(piece); }
                    }
                    if (delta.containsKey("tool_calls") && !delta.isNull("tool_calls")) {
                        int position = 0;
                        for (JsonValue value : delta.getJsonArray("tool_calls")) {
                            JsonObject fragment = value.asJsonObject();
                            int        index    = fragment.getInt("index", position++);
                            Call       call     = calls.computeIfAbsent(index, i -> new Call());
                            if (fragment.containsKey("id") && !fragment.isNull("id")) { call.id = fragment.getString("id"); }
                            JsonObject function = fragment.containsKey("function") && !fragment.isNull("function") ? fragment.getJsonObject("function") : null;
                            if (function != null) {
                                if (function.containsKey("name") && !function.isNull("name")) { call.name.append(function.getString("name")); }
                                if (function.containsKey("arguments") && !function.isNull("arguments")) { call.arguments.append(function.getString("arguments")); }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The model server could not be reached: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the model server", e);
        }
        JsonArrayBuilder result = Json.createArrayBuilder();
        if (!text.isEmpty()) { result.add(Json.createObjectBuilder().add("type", "text").add("text", text.toString())); }
        calls.forEach((index, call) -> result.add(toolUse(call.id == null ? "call_" + index : call.id, call.name.toString(), parse(call.arguments.toString()))));
        return result.build();
    }


    private HttpRequest request(final JsonArray tools, final JsonArray messages, final boolean stream, final Duration timeout) {
        JsonObjectBuilder body = Json.createObjectBuilder().add("model", model).add("messages", convert(messages));
        if (maxTokens != null) { body.add("max_tokens", maxTokens); }
        if (stream) { body.add("stream", true); }
        if (!tools.isEmpty()) {
            JsonArrayBuilder functions = Json.createArrayBuilder();
            for (JsonValue value : tools) {
                JsonObject tool = value.asJsonObject();
                functions.add(Json.createObjectBuilder().add("type", "function").add("function", Json.createObjectBuilder()
                                  .add("name", tool.getString("name")).add("description", tool.getString("description", ""))
                                  .add("parameters", tool.containsKey("input_schema") ? tool.getJsonObject("input_schema") : Json.createObjectBuilder().add("type", "object").build())));
            }
            body.add("tools", functions);
        }
        return HttpRequest.newBuilder(endpoint).timeout(timeout).header("content-type", "application/json").header("authorization", "Bearer " + apiKey)
                          .POST(HttpRequest.BodyPublishers.ofString(body.build().toString())).build();
    }

    /** Anthropic-shaped messages to OpenAI-shaped ones, with the system prompt first. */
    static JsonArray convert(final JsonArray messages) {
        JsonArrayBuilder result = Json.createArrayBuilder();
        result.add(Json.createObjectBuilder().add("role", "system").add("content", AnthropicLlm.SYSTEM));
        for (JsonValue value : messages) {
            JsonObject message = value.asJsonObject();
            String     role    = message.getString("role", "user");
            JsonValue  content = message.get("content");
            if (content == null || content.getValueType() == JsonValue.ValueType.STRING) {
                result.add(Json.createObjectBuilder().add("role", role).add("content", content == null ? "" : message.getString("content")));
                continue;
            }
            StringBuilder    text      = new StringBuilder();
            JsonArrayBuilder toolCalls = Json.createArrayBuilder();
            boolean          hasCalls  = false;
            for (JsonValue item : content.asJsonArray()) {
                JsonObject block = item.asJsonObject();
                switch (block.getString("type", "")) {
                    case "text"        -> text.append(text.isEmpty() ? "" : "\n").append(block.getString("text", ""));
                    case "tool_use"    -> {
                        hasCalls = true;
                        toolCalls.add(Json.createObjectBuilder().add("id", block.getString("id")).add("type", "function")
                                          .add("function", Json.createObjectBuilder().add("name", block.getString("name")).add("arguments", block.getJsonObject("input").toString())));
                    }
                    case "tool_result" -> result.add(Json.createObjectBuilder().add("role", "tool").add("tool_call_id", block.getString("tool_use_id"))
                                                         .add("content", (block.getBoolean("is_error", false) ? "Error: " : "") + resultText(block)));
                    default            -> { }
                }
            }
            if (role.equals("assistant")) {
                JsonObjectBuilder assistant = Json.createObjectBuilder().add("role", "assistant").add("content", text.toString());
                if (hasCalls) { assistant.add("tool_calls", toolCalls); }
                result.add(assistant);
            } else if (!text.isEmpty()) {
                result.add(Json.createObjectBuilder().add("role", role).add("content", text.toString()));
            }
        }
        return result.build();
    }

    private static String resultText(final JsonObject block) {
        JsonValue content = block.get("content");
        if (content == null) { return ""; }
        if (content.getValueType() == JsonValue.ValueType.STRING) { return block.getString("content"); }
        return content.toString();
    }

    private static JsonObject toolUse(final String id, final String name, final JsonObject input) {
        return Json.createObjectBuilder().add("type", "tool_use").add("id", id).add("name", name).add("input", input).build();
    }

    private static JsonObject arguments(final JsonObject function) {
        JsonValue arguments = function.get("arguments");
        if (arguments == null || arguments.getValueType() == JsonValue.ValueType.NULL) { return Json.createObjectBuilder().build(); }
        if (arguments.getValueType() == JsonValue.ValueType.OBJECT) { return arguments.asJsonObject(); }
        return parse(function.getString("arguments"));
    }

    /** The arguments of a tool call; an empty object if the model sent none or invalid JSON (the tool then reports what is missing). */
    private static JsonObject parse(final String json) {
        if (json == null || json.isBlank()) { return Json.createObjectBuilder().build(); }
        try {
            return Json.createReader(new StringReader(json)).readObject();
        } catch (RuntimeException e) {
            return Json.createObjectBuilder().build();
        }
    }


    /** A tool call while it is being streamed. */
    private static final class Call {
        private       String        id;
        private final StringBuilder name      = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
