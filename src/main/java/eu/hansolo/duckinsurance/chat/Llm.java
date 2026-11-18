package eu.hansolo.duckinsurance.chat;

import jakarta.json.JsonArray;

import java.util.function.Consumer;

/** One request to a language model. Messages and content blocks use the shape of the Anthropic Messages API. */
public interface Llm {

    /**
     * @param tools    the tool definitions ({@code name}, {@code description}, {@code input_schema}); may be empty
     * @param messages the conversation so far: {@code role} and {@code content} (a string or an array of blocks)
     * @return the content blocks of the model's reply: {@code text} and {@code tool_use} blocks
     */
    JsonArray complete(JsonArray tools, JsonArray messages);

    /**
     * Like {@link #complete(JsonArray, JsonArray)}, but reports the text while the model writes it. The consumer may throw to stop the request.
     * The default implementation reports the whole text at the end.
     */
    default JsonArray complete(final JsonArray tools, final JsonArray messages, final Consumer<String> onText) {
        JsonArray reply = complete(tools, messages);
        for (var value : reply) {
            var block = value.asJsonObject();
            if ("text".equals(block.getString("type", ""))) { onText.accept(block.getString("text", "")); }
        }
        return reply;
    }
}
