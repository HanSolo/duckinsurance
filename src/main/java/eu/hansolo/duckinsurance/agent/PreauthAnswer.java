package eu.hansolo.duckinsurance.agent;

import java.math.BigDecimal;
import java.util.List;


/**
 * @param status           APPROVED, PARTIAL, DENIED or NEEDS_INFO
 * @param missing          what the insurer wants to see before it decides (NEEDS_INFO), for example "referralDocument"
 * @param approvedPerSession what the insurer pays per session (may be less than the clinic charges)
 * @param source           "agentic-ai" if the model judged inside the limits, "rules" if only the code decided
 */
public record PreauthAnswer(String status, int approvedSessions, BigDecimal approvedPerSession, BigDecimal approvedTotal, String reason, List<String> missing, long policyId, String policyNumber, String source) {
}
