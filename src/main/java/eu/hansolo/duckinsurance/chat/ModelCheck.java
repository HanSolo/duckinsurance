package eu.hansolo.duckinsurance.chat;

import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Asks the model server whether it is there. For Ollama it also checks that the configured model has been pulled (GET /api/tags). For the Anthropic API and OpenAI-format services it only reports the settings. */
public final class ModelCheck {

    /** state is ok, warn or bad (used as a CSS class). */
    public record Result(String state, String text) {
    }

    private ModelCheck() {
    }


    public static Result run() {
        String base = LlmSettings.baseUrl();
        String model = LlmSettings.model();
        // The Anthropic API has no model list without a key and no /api/tags; key and model are checked by the first request
        if (base.contains("api.anthropic.com")) {
            return new Result("ok", "Claude API at " + base + ", model " + model + " (key and model are checked on the first request)");
        }
        if (LlmSettings.provider().equals("openai")) {
            return new Result("ok", "OpenAI-format API at " + base + ", model " + model + " (key and model are checked on the first request)");
        }
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + "/api/tags")).timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return new Result("warn", base + " answers, but does not list models (not an Ollama?). Model " + model + " is not verified");
            }
            JsonObject tags = Json.createReader(new StringReader(response.body())).readObject();
            for (JsonValue value : tags.getJsonArray("models")) {
                String name = value.asJsonObject().getString("name", "");
                if (name.equals(model) || name.equals(model + ":latest")) return new Result("ok", "Ollama at " + base + " is reachable, model " + model + " is available");
            }
            return new Result("warn", "Ollama at " + base + " is reachable, but model " + model + " is not pulled. Run: ollama pull " + model);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result("bad", "Interrupted while checking " + base);
        } catch (Exception e) {
            return new Result("bad", "Model server at " + base + " is not reachable (" + e.getClass().getSimpleName() + "). Is Ollama running? On Linux it must listen on 0.0.0.0");
        }
    }
}
