package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.agent.ClinicClient;
import eu.hansolo.duckinsurance.agent.PhysioClient;
import eu.hansolo.duckinsurance.agent.WeatherClient;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;

/** The indicators on the claims page: are the two clinics and the public weather MCP server, which the fraud agent asks, reachable? */
@Named
@ViewScoped
public class FraudAgentBean implements Serializable {

    @Inject
    private ClinicClient clinic;
    @Inject
    private PhysioClient physio;
    @Inject
    private WeatherClient weather;
    private String       state       = "warn";
    private String       text        = "";
    private String       physioState = "warn";
    private String       physioText  = "";
    private String       weatherState = "warn";
    private String       weatherText  = "";


    @PostConstruct
    public void check() {
        if (!DemoFeatures.fraudAgentOn()) {
            state = "warn";
            text  = "Fraud agent switched off: claims get the rule-based check";
            physioState = "warn";
            physioText  = "";
            weatherState = "warn";
            weatherText  = "";
            return;
        }
        ClinicClient.Status vet = clinic.check();
        state = vet.state();
        text  = vet.text();
        ClinicClient.Status therapy = physio.check();
        physioState = therapy.state();
        physioText  = therapy.text();
        ClinicClient.Status sky = weather.check();
        weatherState = sky.state();
        weatherText  = sky.text();
    }

    public boolean isOff() { return physioText.isEmpty(); }
    public String getState() { return state; }
    public String getText() { return text; }
    public String getPhysioState() { return physioState; }
    public String getPhysioText() { return physioText; }
    public String getWeatherState() { return weatherState; }
    public String getWeatherText() { return weatherText; }
}
