package eu.hansolo.duckinsurance.mcp;

import eu.hansolo.mcp.api.Elicitation;

import java.util.Optional;


/**
 * The question "please send the referral document" that request_preauthorization asks inside the call (MCP multi round-trip request): a form with one required text field.
 * This is the only place that builds and reads a text field of the server library's {@link Elicitation}. DuckTools and the DuckClinic use only the boolean field of a confirmation.
 * If the library names the text field differently, change the two lines below and nothing else.
 */
final class DocumentForm {
    static final String KEY = "referralDocument";

    private DocumentForm() {
    }


    static Elicitation.Form form(String message) {
        return Elicitation.form(message).string(KEY, p -> p.title("Veterinary referral document").required());
    }

    static Optional<String> read(Elicitation.Answer answer) {
        return answer.string(KEY);
    }
}
