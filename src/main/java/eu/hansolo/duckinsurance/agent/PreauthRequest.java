package eu.hansolo.duckinsurance.agent;

import java.math.BigDecimal;
import java.time.LocalDate;


/**
 * The CDI event that starts the pre-authorization agent: a clinic asks whether the insurer will pay for a treatment plan.
 *
 * @param correlation      identifies this call, the answer is stored under it
 * @param referralId       the referral that all three applications carry (REF-...)
 * @param ownerLastName    shared key together with the duck's name (the applications have different ids)
 * @param referralDocument the veterinary referral, or null if the clinic did not send it (yet)
 */
public record PreauthRequest(String correlation, String referralId, String ownerLastName, String duckName, String provider, String treatmentName, String diagnosis, int sessions, BigDecimal pricePerSession, LocalDate firstSession, String referralDocument) {
}
