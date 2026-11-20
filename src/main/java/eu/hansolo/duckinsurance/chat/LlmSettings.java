package eu.hansolo.duckinsurance.chat;

import eu.hansolo.duckinsurance.config.AppConfig;

/**
 * Where the model of the Assistant comes from, read with {@link AppConfig}: the file llm.env next to compose.yaml, which compose loads into the environment.
 * The defaults point at the Ollama on the host, which speaks the Anthropic Messages API. Names: LLM_BASE_URL (default http://host.docker.internal:11434), LLM_API_KEY (default ollama, any text for Ollama),
 * LLM_MODEL (default qwen3.8:27b); the older ANTHROPIC_BASE_URL, ANTHROPIC_API_KEY and ANTHROPIC_MODEL still work. ANTHROPIC_MAX_TOKENS (1024), AGENT_MAX_ROUNDS (8).
 * With a real Anthropic key set LLM_BASE_URL=https://api.anthropic.com and a Claude model name.
 */
public final class LlmSettings {

    private LlmSettings() {
    }


    public static Llm create() {
        return new AnthropicLlm(baseUrl(), value("LLM_API_KEY", "ANTHROPIC_API_KEY", "ollama"), model(), Integer.parseInt(value("ANTHROPIC_MAX_TOKENS", "1024")));
    }

    public static String baseUrl() {
        String url = value("LLM_BASE_URL", "ANTHROPIC_BASE_URL", "http://host.docker.internal:11434");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static String model() {
        return value("LLM_MODEL", "ANTHROPIC_MODEL", "qwen3.8:27b");
    }

    public static int maxRounds() {
        return Integer.parseInt(value("AGENT_MAX_ROUNDS", "8"));
    }

    private static String value(String name, String fallback) {
        return AppConfig.get(name, fallback);
    }

    /** The LLM_* name, or the older ANTHROPIC_* name if only that is set. */
    private static String value(String name, String oldName, String fallback) {
        return AppConfig.get(name, AppConfig.get(oldName, fallback));
    }
}
