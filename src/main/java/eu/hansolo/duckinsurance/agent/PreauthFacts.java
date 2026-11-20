package eu.hansolo.duckinsurance.agent;

import java.math.BigDecimal;


/**
 * What the code found out about the request. These numbers are hard limits: the model may only decide inside them.
 *
 * @param blocker   null if the request can be decided, otherwise NO_POLICY, WAITING_PERIOD, NEEDS_DOCUMENT, TOO_LATE or EXHAUSTED
 * @param remaining coverage left on the policy after claims and reservations
 * @param perSession what the insurer pays per session: the price of the clinic, but at most the cap
 * @param maxSessions the most sessions the rules allow for this request
 */
public record PreauthFacts(PreauthRequest request, String blocker, String message, long policyId, String policyNumber, BigDecimal coverage, BigDecimal consumed, BigDecimal reserved, BigDecimal remaining, BigDecimal perSession, int maxSessions) {
}
