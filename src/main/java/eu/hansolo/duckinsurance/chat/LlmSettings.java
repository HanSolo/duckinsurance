package eu.hansolo.duckinsurance.chat;

import eu.hansolo.duckinsurance.config.AppConfig;

/**
 * Where the model of the Assistant comes from, read with {@link AppConfig}: the file llm.env next to compose.yaml, which compose loads into the environment.
 * The defaults point at the Ollama on the host, which speaks the Anthropic Messages API. Names: LLM_BASE_URL (default http://host.docker.internal:11434), LLM_API_KEY (default ollama, any text for Ollama),
 * LLM_MODEL (default qwen3.8:27b); the older ANTHROPIC_BASE_URL, ANTHROPIC_API_KEY and ANTHROPIC_MODEL still work. ANTHROPIC_MAX_TOKENS (1024), AGENT_MAX_ROUNDS (8).
 * With a real Anthropic key set LLM_BASE_URL=https://api.anthropic.com and a Claude model name.
 * LLM_PROVIDER picks the client: openai uses {@link OpenAiLlm} (Chat Completions, LLM_BASE_URL with /v1); ollama and anthropic use {@link AnthropicLlm} (Ollama 0.14+ speaks the Messages API).
 * With openai, max_tokens is only sent if LLM_MAX_TOKENS or ANTHROPIC_MAX_TOKENS is set, because newer OpenAI models reject it.
 * Optional: ASSISTANT_PROVIDER, ASSISTANT_BASE_URL, ASSISTANT_API_KEY and ASSISTANT_MODEL override the LLM_* values for the Assistant only.
 */
public final class LlmSettings {

    private LlmSettings() {
    }


    public static Llm create() {
        String apiKey = assistant("ASSISTANT_API_KEY", "LLM_API_KEY", "ANTHROPIC_API_KEY", "ollama");
        if (provider().equals("openai")) {
            String limit = AppConfig.get("LLM_MAX_TOKENS", AppConfig.get("ANTHROPIC_MAX_TOKENS", ""));
            return new OpenAiLlm(baseUrl(), apiKey, model(), limit.isEmpty() ? null : Integer.valueOf(limit));
        }
        return new AnthropicLlm(baseUrl(), apiKey, model(), Integer.parseInt(value("ANTHROPIC_MAX_TOKENS", "1024")));
    }

    /** ollama, anthropic (or claude) or openai. */
    public static String provider() {
        return AppConfig.get("ASSISTANT_PROVIDER", AppConfig.get("LLM_PROVIDER", "ollama")).toLowerCase();
    }

    public static String baseUrl() {
        String url = assistant("ASSISTANT_BASE_URL", "LLM_BASE_URL", "ANTHROPIC_BASE_URL", "http://host.docker.internal:11434");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static String model() {
        return assistant("ASSISTANT_MODEL", "LLM_MODEL", "ANTHROPIC_MODEL", "qwen3.8:27b");
    }

    public static int maxRounds() {
        return Integer.parseInt(value("AGENT_MAX_ROUNDS", "8"));
    }

    private static String value(String name, String fallback) {
        return AppConfig.get(name, fallback);
    }

    /** An optional ASSISTANT_* override, else the LLM_* or older ANTHROPIC_* name. */
    private static String assistant(String override, String name, String oldName, String fallback) {
        return AppConfig.get(override, value(name, oldName, fallback));
    }

    /** The LLM_* name, or the older ANTHROPIC_* name if only that is set. */
    private static String value(String name, String oldName, String fallback) {
        return AppConfig.get(name, AppConfig.get(oldName, fallback));
    }
}
