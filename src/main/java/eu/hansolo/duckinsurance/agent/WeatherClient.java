package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.config.AppConfig;
import eu.hansolo.mcp.client.McpClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * A public weather service on the internet, used by the fraud agent through MCP: DuckInsurance is the MCP client here, the server belongs to somebody else.
 * The default is the free, keyless Open-Meteo MCP server at https://open-meteo.caseyjhand.com/mcp (open source: github.com/cyanheads/open-meteo-mcp-server, weather data by Open-Meteo.com, CC BY 4.0,
 * free for non-commercial use). Tools used: openmeteo_search_locations (place name to coordinates) and openmeteo_get_historical (archive from 1940).
 * WEATHER_MCP_URL changes the endpoint, for example to a self-hosted copy of the same server; WEATHER_MCP_URL=off switches the weather check off.
 * The service is not under our control, so it is allowed to fail: the check then says the weather is unknown and the claim is judged without it.
 */
@ApplicationScoped
public class WeatherClient {
    public static final  String DEFAULT_URL = "https://open-meteo.caseyjhand.com/mcp";
    private static final String GEOCODE     = "openmeteo_search_locations";
    private static final String HISTORY     = "openmeteo_get_historical";

    /** One day of weather at a place. Values the service did not send are null. */
    public record Day(LocalDate date, Integer code, Double precipitationMm, Double snowfallCm, Double gustKmh, Double maxC, Double minC) {
    }

    /** The weather around a date at a place, as the service reported it. */
    public record Report(String place, double latitude, double longitude, List<Day> days) {
    }

    /** Successful answers, so that pressing Check again in a demo does not depend on the internet a second time. The weather of a past day does not change. */
    private final Map<String, Report> cache = new ConcurrentHashMap<>();


    public static boolean enabled() {
        return !"off".equalsIgnoreCase(AppConfig.get("WEATHER_MCP_URL", DEFAULT_URL));
    }

    public URI endpoint() {
        return URI.create(AppConfig.get("WEATHER_MCP_URL", DEFAULT_URL));
    }

    /** Is the weather server reachable, and does it offer the two tools? A short timeout, because the claims page waits for this. */
    public ClinicClient.Status check() {
        if (!enabled()) return new ClinicClient.Status("warn", "Weather check switched off (WEATHER_MCP_URL=off)");
        try {
            List<String> names = client(Duration.ofSeconds(5)).tools().stream().map(t -> t.getString("name", "")).toList();
            for (String needed : List.of(GEOCODE, HISTORY)) {
                if (!names.contains(needed)) return new ClinicClient.Status("bad", "Public weather MCP server reachable, but the tool " + needed + " is missing");
            }
            return new ClinicClient.Status("ok", "Public weather MCP server reachable at " + endpoint().getHost() + ", " + names.size() + " tools");
        } catch (RuntimeException e) {
            return new ClinicClient.Status("bad", "Public weather MCP server not reachable at " + endpoint().getHost() + " (claims are judged without the weather): " + e.getMessage());
        }
    }

    /**
     * The weather in a town from one day before to one day after the date.
     *
     * @throws WeatherUnavailableException if the server cannot be reached, fails, or does not know the place
     */
    public Report around(String town, LocalDate date) {
        String key = town.toLowerCase(Locale.ROOT) + "|" + date;
        Report known = cache.get(key);
        if (known != null) return known;

        McpClient  client = client(Duration.ofSeconds(15));
        JsonObject places = call(client, GEOCODE, Json.createObjectBuilder().add("name", town).add("count", 1).build());
        JsonArray  found  = places.getJsonArray("results");
        if (found == null || found.isEmpty()) throw new WeatherUnavailableException("The weather service does not know the place " + town, null);
        JsonObject place = found.getJsonObject(0);
        double     lat   = place.getJsonNumber("latitude").doubleValue();
        double     lon   = place.getJsonNumber("longitude").doubleValue();
        String     name  = place.getString("name", town) + (place.containsKey("country") ? ", " + place.getString("country", "") : "");

        JsonObject history = call(client, HISTORY, Json.createObjectBuilder()
                .add("latitude", lat).add("longitude", lon)
                .add("start_date", date.minusDays(1).toString()).add("end_date", date.plusDays(1).toString())
                .add("daily_variables", Json.createArrayBuilder().add("weather_code").add("precipitation_sum").add("snowfall_sum").add("wind_gusts_10m_max").add("temperature_2m_max").add("temperature_2m_min"))
                .build());
        JsonArray daily = history.getJsonArray("daily");
        if (daily == null || daily.isEmpty()) throw new WeatherUnavailableException("The weather service returned no data for " + name + " around " + date, null);
        List<Day> days = new ArrayList<>();
        for (JsonValue v : daily) {
            JsonObject d = v.asJsonObject();
            Double code = number(d, "weather_code");
            days.add(new Day(LocalDate.parse(d.getString("time")), code == null ? null : code.intValue(), number(d, "precipitation_sum"), number(d, "snowfall_sum"), number(d, "wind_gusts_10m_max"), number(d, "temperature_2m_max"), number(d, "temperature_2m_min")));
        }
        Report report = new Report(name, lat, lon, List.copyOf(days));
        cache.put(key, report);
        return report;
    }

    private JsonObject call(McpClient client, String tool, JsonObject arguments) {
        try {
            return McpJson.data(tool, client.callTool(tool, arguments));
        } catch (RuntimeException e) {
            throw new WeatherUnavailableException("The weather tool " + tool + " failed: " + e.getMessage(), e);
        }
    }

    private static Double number(JsonObject o, String key) {
        return o.containsKey(key) && o.get(key) instanceof JsonNumber n ? n.doubleValue() : null;
    }

    private McpClient client(Duration timeout) {
        return McpClient.builder(endpoint()).clientInfo("duckinsurance-fraud-agent", "1.0").timeout(timeout).build();
    }
}
