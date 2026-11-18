package eu.hansolo.duckinsurance.chat;

import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonArrayBuilder;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.util.ArrayList;
import java.util.List;


/** The tool-calling loop with the model's native tool use: ask the model, run the tools it asks for, give it the results, repeat until it answers or the round limit is reached. */
public final class AgentLoop {
    private final Llm         llm;
    private final ToolBackend backend;
    private final int         maxRounds;


    public AgentLoop(final Llm llm, final ToolBackend backend, final int maxRounds) {
        this.llm       = llm;
        this.backend   = backend;
        this.maxRounds = maxRounds;
    }


    /** Progress of a run, for a user interface. All methods are called on the thread that runs the loop. */
    public interface Listener {

        /** A new model request starts; text shown so far for the previous request is obsolete. */
        default void round(final int number) { }

        /** Text of the model arrives piece by piece. */
        default void text(final String delta) { }

        default void toolCalled(final String name, final JsonObject arguments) { }

        default void toolReturned(final Call call) { }

        /** Return true to stop the run at the next opportunity. */
        default boolean cancelled() { return false; }
    }


    public Answer run(final String question) {
        return run(List.of(), question, new Listener() { });
    }

    /** @param history earlier turns as plain {@code role} / {@code content} text messages, oldest first */
    public Answer run(final List<JsonObject> history, final String question, final Listener listener) {
        JsonArray        tools    = backend.tools();
        List<JsonObject> messages = new ArrayList<>(history);
        List<Call>       calls    = new ArrayList<>();
        messages.add(Json.createObjectBuilder().add("role", "user").add("content", question).build());
        for (int round = 1; round <= maxRounds; round++) {
            if (listener.cancelled()) { return new Answer("Stopped.", round - 1, false, calls, null); }
            listener.round(round);
            JsonArray reply = llm.complete(tools, Json.createArrayBuilder(messages).build(), listener::text);
            messages.add(Json.createObjectBuilder().add("role", "assistant").add("content", reply).build());
            JsonArrayBuilder results = Json.createArrayBuilder();
            boolean          asked   = false;
            for (JsonValue value : reply) {
                JsonObject block = value.asJsonObject();
                if (!"tool_use".equals(block.getString("type", ""))) { continue; }
                asked = true;
                JsonObject          input   = block.containsKey("input") ? block.getJsonObject("input") : Json.createObjectBuilder().build();
                listener.toolCalled(block.getString("name"), input);
                ToolBackend.Outcome outcome = backend.call(block.getString("name"), input);
                Call                call    = new Call(block.getString("name"), input, outcome.text(), outcome.isError());
                calls.add(call);
                listener.toolReturned(call);
                results.add(Json.createObjectBuilder().add("type", "tool_result").add("tool_use_id", block.getString("id")).add("content", outcome.text()).add("is_error", outcome.isError()));
            }
            if (!asked) { return new Answer(text(reply), round, false, calls, null); }
            messages.add(Json.createObjectBuilder().add("role", "user").add("content", results).build());
        }
        return new Answer("Stopped after " + maxRounds + " rounds without a final answer.", maxRounds, true, calls, null);
    }

    private static String text(final JsonArray blocks) {
        StringBuilder text = new StringBuilder();
        for (JsonValue value : blocks) {
            JsonObject block = value.asJsonObject();
            if ("text".equals(block.getString("type", ""))) {
                if (!text.isEmpty()) { text.append('\n'); }
                text.append(block.getString("text", ""));
            }
        }
        return text.toString();
    }


    /** One tool call the model asked for. */
    public record Call(String name, JsonObject arguments, String result, boolean isError) {

        public JsonObject toJson() {
            return Json.createObjectBuilder().add("name", name).add("arguments", arguments).add("result", result).add("isError", isError).build();
        }
    }


    /** The final answer, the number of model requests it took, whether the round limit cut it short, every tool call in order, and an error text if the request failed. */
    public record Answer(String text, int rounds, boolean truncated, List<Call> calls, String error) {

        public JsonObject toJson() {
            JsonArrayBuilder toolCalls = Json.createArrayBuilder();
            for (Call call : calls) { toolCalls.add(call.toJson()); }
            var json = Json.createObjectBuilder().add("answer", text).add("rounds", rounds).add("truncated", truncated).add("toolCalls", toolCalls);
            if (error != null) { json.add("error", error); }
            return json.build();
        }
    }
}
