package eu.hansolo.duckinsurance.agent;

/** The public weather server could not be reached, failed or did not know the place. The weather is then unknown, which tells nothing about the claim. */
public class WeatherUnavailableException extends RuntimeException {

    public WeatherUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
