package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.agent.ClinicClient;
import eu.hansolo.duckinsurance.agent.PhysioClient;
import eu.hansolo.duckinsurance.config.AppConfig;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;

/** The indicators on the claims page: are the two clinics, which the fraud agent asks, reachable? */
@Named
@ViewScoped
public class FraudAgentBean implements Serializable {

    @Inject
    private ClinicClient clinic;
    @Inject
    private PhysioClient physio;
    private String       state       = "warn";
    private String       text        = "";
    private String       physioState = "warn";
    private String       physioText  = "";


    @PostConstruct
    public void check() {
        if ("off".equalsIgnoreCase(AppConfig.get("DUCK_FRAUD_AGENT", "on"))) {
            state = "warn";
            text  = "Fraud agent switched off (DUCK_FRAUD_AGENT=off): claims get the rule-based check";
            physioState = "warn";
            physioText  = "";
            return;
        }
        ClinicClient.Status vet = clinic.check();
        state = vet.state();
        text  = vet.text();
        ClinicClient.Status therapy = physio.check();
        physioState = therapy.state();
        physioText  = therapy.text();
    }

    public boolean isOff() { return physioText.isEmpty(); }
    public String getState() { return state; }
    public String getText() { return text; }
    public String getPhysioState() { return physioState; }
    public String getPhysioText() { return physioText; }
}
