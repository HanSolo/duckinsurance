package eu.hansolo.duckinsurance.event;

import eu.hansolo.duckinsurance.domain.ClaimStatus;

/** CDI event payload fired whenever a claim changes state. */
public record ClaimEvent(Long claimId, ClaimStatus from, ClaimStatus to, String actor) {
}
