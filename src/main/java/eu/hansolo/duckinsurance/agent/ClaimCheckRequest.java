package eu.hansolo.duckinsurance.agent;

/** The CDI event that starts the fraud agent's workflow: one claim to check. */
public record ClaimCheckRequest(long claimId) {
}
