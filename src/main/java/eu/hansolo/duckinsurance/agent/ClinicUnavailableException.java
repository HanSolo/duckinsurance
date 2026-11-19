package eu.hansolo.duckinsurance.agent;

/** The DuckClinic could not be reached or did not answer usably. The claim then gets the rule-based check. */
public class ClinicUnavailableException extends RuntimeException {

    public ClinicUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
