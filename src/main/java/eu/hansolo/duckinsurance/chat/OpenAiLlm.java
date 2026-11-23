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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * Robustness for services that are stricter than the Anthropic API (seen on Groq):
 * <ul>
 *   <li>optional tool parameters are declared nullable, and null arguments are dropped before the tool runs, because models often send {@code "ownerId": null}
 *       and some services validate the call against the schema</li>
 *   <li>a tool call the service rejects ({@code tool_use_failed}, as HTTP 400 or as an error event in the stream) is asked again once, with the reason</li>
 *   <li>a rate limit (HTTP 429) is waited out once, as long as the service asks for at most {@value #MAX_WAIT_SECONDS} seconds</li>
 * </ul>
 */
public final class OpenAiLlm implements Llm {
    private static final int     MAX_WAIT_SECONDS = 30;
    private static final Pattern TRY_AGAIN        = Pattern.compile("try again in ([0-9.]+)\\s*(ms|s)");
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
        return withRetry(hint -> completeOnce(tools, messages, hint));
    }

    @Override public JsonArray complete(final JsonArray tools, final JsonArray messages, final Consumer<String> onText) {
        return withRetry(hint -> streamOnce(tools, messages, onText, hint));
    }

    /** Runs one request, and once more after a rejected tool call or a short rate limit. */
    private JsonArray withRetry(final java.util.function.Function<String, JsonArray> attempt) {
        String hint = null;
        for (int round = 1; ; round++) {
            try {
                return attempt.apply(hint);
            } catch (Retry retry) {
                if (round >= 3 || (retry.hint != null && hint != null) || (retry.hint == null && retry.waitMillis < 0)) { throw new IllegalStateException(retry.getMessage()); }
                if (retry.hint != null) { hint = retry.hint; }
                if (retry.waitMillis > 0) {
                    try { Thread.sleep(retry.waitMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while waiting out a rate limit", e); }
                }
            }
        }
    }

    private JsonArray completeOnce(final JsonArray tools, final JsonArray messages, final String hint) {
        HttpRequest request = request(tools, messages, false, Duration.ofSeconds(180), hint);
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) { throw failure(response.statusCode(), response.body(), response.headers().firstValue("retry-after").orElse(null)); }
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
    private JsonArray streamOnce(final JsonArray tools, final JsonArray messages, final Consumer<String> onText, final String hint) {
        HttpRequest          request = request(tools, messages, true, Duration.ofSeconds(600), hint);
        StringBuilder        text    = new StringBuilder();
        Map<Integer, Call>   calls   = new TreeMap<>();
        try {
            HttpResponse<Stream<String>> response = http.send(request, HttpResponse.BodyHandlers.ofLines());
            if (response.statusCode() != 200) {
                String body = response.body().reduce("", (a, b) -> a + b);
                throw failure(response.statusCode(), body, response.headers().firstValue("retry-after").orElse(null));
            }
            try (Stream<String> lines = response.body()) {
                for (var iterator = lines.iterator(); iterator.hasNext(); ) {
                    String line = iterator.next();
                    if (!line.startsWith("data:")) { continue; }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || data.equals("[DONE]")) { continue; }
                    JsonObject event = Json.createReader(new StringReader(data)).readObject();
                    if (event.containsKey("error") && !event.isNull("error")) {
                        String error = event.get("error").toString();
                        if (error.contains("tool_use_failed")) { throw new Retry("The model server reported an error: " + error, rejection(error), 0); }
                        throw new IllegalStateException("The model server reported an error: " + error);
                    }
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


    private HttpRequest request(final JsonArray tools, final JsonArray messages, final boolean stream, final Duration timeout, final String hint) {
        JsonArray converted = convert(messages);
        if (hint != null) { converted = Json.createArrayBuilder(converted).add(Json.createObjectBuilder().add("role", "user").add("content", hint)).build(); }
        JsonObjectBuilder body = Json.createObjectBuilder().add("model", model).add("messages", converted);
        if (maxTokens != null) { body.add("max_tokens", maxTokens); }
        if (stream) { body.add("stream", true); }
        if (!tools.isEmpty()) {
            JsonArrayBuilder functions = Json.createArrayBuilder();
            for (JsonValue value : tools) {
                JsonObject tool = value.asJsonObject();
                functions.add(Json.createObjectBuilder().add("type", "function").add("function", Json.createObjectBuilder()
                                  .add("name", tool.getString("name")).add("description", tool.getString("description", ""))
                                  .add("parameters", tool.containsKey("input_schema") ? nullableOptionals(tool.getJsonObject("input_schema")) : Json.createObjectBuilder().add("type", "object").build())));
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

    /** Declares every property that is not required as nullable ({@code "type": ["integer", "null"]}), so that a service that validates tool calls accepts {@code null}. */
    static JsonObject nullableOptionals(final JsonObject schema) {
        if (!schema.containsKey("properties") || schema.get("properties").getValueType() != JsonValue.ValueType.OBJECT) { return schema; }
        java.util.Set<String> required = new java.util.HashSet<>();
        if (schema.containsKey("required") && schema.get("required").getValueType() == JsonValue.ValueType.ARRAY) {
            for (JsonValue name : schema.getJsonArray("required")) { if (name.getValueType() == JsonValue.ValueType.STRING) { required.add(((jakarta.json.JsonString) name).getString()); } }
        }
        JsonObjectBuilder properties = Json.createObjectBuilder();
        schema.getJsonObject("properties").forEach((name, value) -> {
            if (!required.contains(name) && value.getValueType() == JsonValue.ValueType.OBJECT && value.asJsonObject().containsKey("type")
                && value.asJsonObject().get("type").getValueType() == JsonValue.ValueType.STRING) {
                properties.add(name, Json.createObjectBuilder(value.asJsonObject()).add("type", Json.createArrayBuilder().add(value.asJsonObject().getString("type")).add("null")));
            } else {
                properties.add(name, value);
            }
        });
        return Json.createObjectBuilder(schema).add("properties", properties).build();
    }

    /** What went wrong with a request: a {@link Retry} for a rejected tool call or a short rate limit, otherwise a plain error. */
    private static RuntimeException failure(final int status, final String body, final String retryAfter) {
        String text = "The model server answered with status " + status + ": " + body;
        if (status == 400 && body.contains("tool_use_failed")) { return new Retry(text, rejection(body), 0); }
        if (status == 429) {
            long millis = -1;
            Matcher matcher = TRY_AGAIN.matcher(body);
            if (matcher.find()) {
                double value = Double.parseDouble(matcher.group(1));
                millis = (long) Math.ceil(matcher.group(2).equals("ms") ? value : value * 1000);
            } else if (retryAfter != null && retryAfter.matches("[0-9.]+")) {
                millis = (long) Math.ceil(Double.parseDouble(retryAfter) * 1000);
            }
            if (millis >= 0 && millis <= MAX_WAIT_SECONDS * 1000L) { return new Retry(text, null, millis + 250); }
            return new IllegalStateException(text + " (rate limit of the model service: wait a moment, use a smaller model or a higher service tier)");
        }
        return new IllegalStateException(text);
    }

    /** The message that tells the model why its tool call was rejected. */
    private static String rejection(final String error) {
        String reason = error;
        try {
            JsonObject json = Json.createReader(new StringReader(error)).readObject();
            JsonObject inner = json.containsKey("error") && json.get("error").getValueType() == JsonValue.ValueType.OBJECT ? json.getJsonObject("error") : json;
            reason = inner.getString("message", error);
        } catch (RuntimeException e) {
            // keep the raw text
        }
        return "Your last tool call was rejected: " + reason + " Call the tool again with arguments that match its schema; leave out optional arguments you do not need instead of sending null.";
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
        if (arguments.getValueType() == JsonValue.ValueType.OBJECT) { return withoutNulls(arguments.asJsonObject()); }
        return parse(function.getString("arguments"));
    }

    /** The arguments of a tool call; an empty object if the model sent none or invalid JSON (the tool then reports what is missing). */
    private static JsonObject parse(final String json) {
        if (json == null || json.isBlank()) { return Json.createObjectBuilder().build(); }
        try {
            return withoutNulls(Json.createReader(new StringReader(json)).readObject());
        } catch (RuntimeException e) {
            return Json.createObjectBuilder().build();
        }
    }

    /** Leaves out arguments the model set to null: for the MCP tools a missing optional argument and a null one mean the same, but only the first passes validation. */
    private static JsonObject withoutNulls(final JsonObject arguments) {
        JsonObjectBuilder result = Json.createObjectBuilder();
        arguments.forEach((name, value) -> { if (value.getValueType() != JsonValue.ValueType.NULL) { result.add(name, value); } });
        return result.build();
    }


    /** A request that is worth one more try: after {@code waitMillis}, and with {@code hint} as an extra message if not null. */
    private static final class Retry extends RuntimeException {
        private final String hint;
        private final long   waitMillis;

        private Retry(final String message, final String hint, final long waitMillis) {
            super(message);
            this.hint       = hint;
            this.waitMillis = waitMillis;
        }
    }


    /** A tool call while it is being streamed. */
    private static final class Call {
        private       String        id;
        private final StringBuilder name      = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
