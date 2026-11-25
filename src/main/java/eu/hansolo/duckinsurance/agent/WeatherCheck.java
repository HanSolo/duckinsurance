package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.domain.Claim;
import eu.hansolo.duckinsurance.domain.Customer;
import eu.hansolo.duckinsurance.domain.Policy;
import eu.hansolo.duckinsurance.service.CustomerService;
import eu.hansolo.duckinsurance.service.PolicyService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;


/**
 * The WEATHER check of the fraud agent: does the weather a claim names (hail, storm, flood, frost, heat) match what the weather really was in the owner's town around the incident date?
 * The numbers come from a public weather MCP server ({@link WeatherClient}). The code reads them, because weather codes and thresholds are easy to get wrong for a language model;
 * the model gets the days and the code's reading and weighs it with everything else.
 */
@ApplicationScoped
public class WeatherCheck {
    /** Gusts from this speed count as a storm (Beaufort 9, strong gale). */
    public static final double STORM_GUST_KMH  = 75;
    /** Rain from this amount on one day can flood a coop or a pond. */
    public static final double FLOOD_DAY_MM    = 20;
    /** A day this hot counts as heat. */
    public static final double HEAT_C          = 30;

    /** Weather events a claim can name, with the words that name them. The order matters: "hailstorm" is hail, not storm. */
    public enum Event {
        HAIL("hail", "hail"),
        THUNDERSTORM("a thunderstorm", "thunder|lightning"),
        STORM("a storm", "storm|gale|hurricane|tornado|gust|high wind|strong wind"),
        FLOOD("heavy rain or a flood", "flood|heavy rain|downpour|cloudburst|torrential"),
        FROST("frost, ice or snow", "frost|frozen|freez|icy|ice |snow|blizzard"),
        HEAT("heat", "heatwave|heat wave|heatstroke|heat stroke|scorching");

        private final String words;
        private final String pattern;

        Event(String words, String pattern) {
            this.words   = words;
            this.pattern = ".*(" + pattern + ").*";
        }

        public String words() {
            return words;
        }
    }

    @Inject
    private WeatherClient   weather;
    @Inject
    private PolicyService   policies;
    @Inject
    private CustomerService customers;


    /** The weather event the description names, if any. */
    public static Optional<Event> claimedEvent(String description) {
        String text = " " + String.valueOf(description).toLowerCase(Locale.ROOT) + " ";
        for (Event event : Event.values()) if (text.matches(event.pattern)) return Optional.of(event);
        return Optional.empty();
    }

    /** Runs the check for a claim and returns what it found, as text for the model, the agent log and the claim list. Never throws: an unreachable server means unknown weather. */
    public String run(Claim claim) {
        Policy          policy = policies.get(claim.getPolicyId());
        Customer        owner  = customers.get(policy.getCustomerId());
        String          town   = owner.getCity();
        Optional<Event> event  = claimedEvent(claim.getDescription());
        if (!WeatherClient.enabled()) return "The weather check is switched off.";
        if (town == null || town.isBlank()) return "The owner has no town on record, so the weather cannot be checked.";
        WeatherClient.Report report;
        try {
            report = weather.around(town, claim.getIncidentDate());
        } catch (RuntimeException e) {
            return "The public weather server could not be reached or did not answer (" + shorten(e.getMessage()) + "). The weather is unknown; do not hold that against the claim.";
        }
        String days = report.days().stream().map(WeatherCheck::line).collect(Collectors.joining("\n"));
        String head = "Weather in " + report.place() + " (owner's town), from the public Open-Meteo weather MCP server:\n" + days + "\n";
        if (event.isEmpty()) return head + "The description names no weather event.";
        return head + "Claimed: " + event.get().words() + " on " + claim.getIncidentDate() + ". " + reading(event.get(), report.days());
    }

    /** What the numbers say about the claimed event. Looks at the day before, the day itself and the day after, because the claimed date can be off by one. */
    static String reading(Event event, List<WeatherClient.Day> days) {
        boolean thunder  = days.stream().anyMatch(d -> d.code() != null && d.code() >= 95);
        double  gust     = days.stream().map(WeatherClient.Day::gustKmh).filter(v -> v != null).mapToDouble(Double::doubleValue).max().orElse(0);
        double  rain     = days.stream().map(WeatherClient.Day::precipitationMm).filter(v -> v != null).mapToDouble(Double::doubleValue).max().orElse(0);
        double  hottest  = days.stream().map(WeatherClient.Day::maxC).filter(v -> v != null).mapToDouble(Double::doubleValue).max().orElse(-99);
        double  coldest  = days.stream().map(WeatherClient.Day::minC).filter(v -> v != null).mapToDouble(Double::doubleValue).min().orElse(99);
        double  snow     = days.stream().map(WeatherClient.Day::snowfallCm).filter(v -> v != null).mapToDouble(Double::doubleValue).sum();
        return switch (event) {
            case HAIL, THUNDERSTORM -> thunder
                    ? "There was a thunderstorm (weather code 95 to 99) within a day of the incident, so " + event.words() + " is plausible."
                    : "There was no thunderstorm (weather code 95 to 99, the only codes that go with hail and lightning) on any of these days, wettest day " + fmt(rain) + " mm. The weather does NOT support " + event.words() + ".";
            case STORM -> gust >= STORM_GUST_KMH || thunder
                    ? "The strongest gust was " + fmt(gust) + " km/h" + (thunder ? " with a thunderstorm" : "") + ", so a storm is plausible."
                    : "The strongest gust was " + fmt(gust) + " km/h (a storm starts at about " + fmt(STORM_GUST_KMH) + " km/h) and there was no thunderstorm. The weather does NOT support a storm.";
            case FLOOD -> rain >= FLOOD_DAY_MM
                    ? "The wettest day had " + fmt(rain) + " mm of rain, so heavy rain is plausible."
                    : "The wettest day had only " + fmt(rain) + " mm of rain (heavy rain starts at about " + fmt(FLOOD_DAY_MM) + " mm a day). The weather does NOT support heavy rain or a flood.";
            case FROST -> coldest <= 0 || snow > 0
                    ? "The coldest temperature was " + fmt(coldest) + " °C" + (snow > 0 ? " with " + fmt(snow) + " cm of snow" : "") + ", so frost, ice or snow is plausible."
                    : "The coldest temperature was " + fmt(coldest) + " °C and there was no snow. The weather does NOT support frost, ice or snow.";
            case HEAT -> hottest >= HEAT_C
                    ? "The hottest temperature was " + fmt(hottest) + " °C, so heat is plausible."
                    : "The hottest temperature was only " + fmt(hottest) + " °C. The weather does NOT support heat.";
        };
    }

    private static String line(WeatherClient.Day d) {
        return d.date() + ": " + (d.code() == null ? "unknown" : describe(d.code()) + " (code " + d.code() + ")")
               + (d.precipitationMm() == null ? "" : ", " + fmt(d.precipitationMm()) + " mm")
               + (d.snowfallCm() == null || d.snowfallCm() == 0 ? "" : ", " + fmt(d.snowfallCm()) + " cm snow")
               + (d.gustKmh() == null ? "" : ", gusts up to " + fmt(d.gustKmh()) + " km/h")
               + (d.minC() == null || d.maxC() == null ? "" : ", " + fmt(d.minC()) + " to " + fmt(d.maxC()) + " °C");
    }

    /** WMO weather interpretation codes, as Open-Meteo uses them. */
    static String describe(int code) {
        return switch (code) {
            case 0 -> "clear sky";
            case 1, 2 -> "mostly clear";
            case 3 -> "overcast";
            case 45, 48 -> "fog";
            case 51, 53, 55, 56, 57 -> "drizzle";
            case 61, 63, 66 -> "rain";
            case 65, 67 -> "heavy rain";
            case 71, 73, 75, 77, 85, 86 -> "snow";
            case 80, 81, 82 -> "rain showers";
            case 95 -> "thunderstorm";
            case 96, 99 -> "thunderstorm with hail";
            default -> "weather";
        };
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String shorten(String s) {
        String t = String.valueOf(s);
        return t.length() > 140 ? t.substring(0, 140) + "…" : t;
    }
}
