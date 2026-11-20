package eu.hansolo.duckinsurance.agent;

import java.util.List;

/**
 * What the agent knows about a claim after it has asked the two clinics. A clinic that could not be reached is not suspicious, it is just unknown.
 *
 * @param claimText        the claim in words: number, duck, owner, policy, amount, incident date, description
 * @param policyType       VET_CARE, PHYSIO, ...
 * @param vetReachable     the DuckClinic answered
 * @param vetDuckKnown     the DuckClinic has a duck with this name for an owner with this last name
 * @param vetVisits        all visits of that duck in the DuckClinic ("date: description")
 * @param vetNearby        the visits within a few days of the incident
 * @param physioReachable  the Duck Physical Therapy Clinic answered
 * @param physioDuckId     the duck's id there, or 0 if the clinic does not know it
 * @param physioAppts      all appointments of that duck there
 * @param physioNearby     the appointments within a few days of the incident
 * @param treatments       what the physical therapy clinic offers
 * @param therapists       who works there
 */
public record ClinicFacts(long claimId, String claimText, String policyType, String description, long duckId, boolean vetReachable, boolean vetDuckKnown, List<String> vetVisits, List<String> vetNearby,
                          boolean physioReachable, long physioDuckId, List<String> physioAppts, List<String> physioNearby, List<PhysioClient.Option> treatments, List<PhysioClient.Option> therapists) {

    public boolean found() {
        return !vetNearby.isEmpty() || !physioNearby.isEmpty();
    }
}
