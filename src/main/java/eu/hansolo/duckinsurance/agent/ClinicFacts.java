package eu.hansolo.duckinsurance.agent;

import java.util.List;

/**
 * What the agent knows about a claim after it has asked the DuckClinic.
 *
 * @param claimText     the claim in words: number, duck, owner, policy, amount, incident date, description
 * @param duckKnown     the clinic has a duck with this name for an owner with this last name
 * @param visits        all visits of that duck in the clinic ("date: description"), newest first
 * @param nearbyVisits  the visits within a few days of the incident
 */
public record ClinicFacts(long claimId, String claimText, boolean duckKnown, List<String> visits, List<String> nearbyVisits) {
}
