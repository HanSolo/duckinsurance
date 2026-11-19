package eu.hansolo.duckinsurance.agent;

/**
 * @param score  0 (plausible) to 100 (very suspicious)
 * @param reason one or two sentences for the adjuster
 * @param source "agentic-ai" if the runtime's LargeLanguageModel judged, "rules" if its answer could not be read
 */
public record Verdict(int score, String reason, String source) {
}
