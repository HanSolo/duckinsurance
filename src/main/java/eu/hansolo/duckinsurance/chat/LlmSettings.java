package eu.hansolo.duckinsurance.chat;

/**
 * Where the model comes from. The defaults point at the Ollama container of compose.yaml, which speaks the Anthropic Messages API.
 * Environment: ANTHROPIC_BASE_URL (default http://host.docker.internal:11434), ANTHROPIC_API_KEY (default ollama, any text for Ollama), ANTHROPIC_MODEL (default qwen3.8:27b),
 * ANTHROPIC_MAX_TOKENS (1024), AGENT_MAX_ROUNDS (8). With a real Anthropic key set ANTHROPIC_BASE_URL=https://api.anthropic.com and a Claude model name.
 */
public final class LlmSettings {

    private LlmSettings() {
    }


    public static Llm create() {
        return new AnthropicLlm(baseUrl(), value("ANTHROPIC_API_KEY", "ollama"), model(), Integer.parseInt(value("ANTHROPIC_MAX_TOKENS", "1024")));
    }

    public static String baseUrl() {
        String url = value("ANTHROPIC_BASE_URL", "http://host.docker.internal:11434");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static String model() {
        return value("ANTHROPIC_MODEL", "qwen3.8:27b");
    }

    public static int maxRounds() {
        return Integer.parseInt(value("AGENT_MAX_ROUNDS", "8"));
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
