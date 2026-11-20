package eu.hansolo.duckinsurance.agent;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;


/**
 * The hard limits of the pre-authorization agent. They are enforced in code, the language model only decides inside them.
 * The same rules are written down in plain language in policies/insurer-agent-policy.md, which is what the model is given as its instructions: change both together.
 */
public final class PreauthPolicy {
    public static final int        MAX_SESSIONS_PER_REFERRAL = 6;
    public static final BigDecimal MAX_PER_SESSION           = new BigDecimal("75.00");
    public static final int        WAITING_PERIOD_DAYS       = 14;
    public static final int        START_WITHIN_DAYS         = 30;

    private PreauthPolicy() {
    }


    /** The policy file as text, for the prompt. */
    public static String text() {
        try (InputStream in = PreauthPolicy.class.getResourceAsStream("/policies/insurer-agent-policy.md")) {
            return in == null ? "(policy file missing)" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(policy file unreadable)";
        }
    }
}
