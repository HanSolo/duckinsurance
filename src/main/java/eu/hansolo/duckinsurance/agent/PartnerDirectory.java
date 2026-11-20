package eu.hansolo.duckinsurance.agent;

import eu.hansolo.duckinsurance.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;


/** Plain REST calls to the two clinics, for the conversation page: their agent logs and the buttons that start a referral or complete a session. */
@ApplicationScoped
public class PartnerDirectory {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();


    public String clinicRest() {
        return AppConfig.get("DUCKCLINIC_REST_URL", "http://host.docker.internal:8080/duck-clinic/api");
    }

    public String physioRest() {
        return AppConfig.get("PHYSIOCLINIC_REST_URL", "http://host.docker.internal:8082/api");
    }

    /** @return the body, or null if the other application cannot be reached */
    public String get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build());
    }

    public String post(String url, String json) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json == null ? "" : json)).build());
    }

    public String delete(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).DELETE().build());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2 ? response.body() : "{\"error\":\"HTTP " + response.statusCode() + ": " + response.body().replace("\"", "'").replace("\n", " ") + "\"}";
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
