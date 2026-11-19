package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.config.AppConfig;
import eu.hansolo.duckinsurance.agent.ClinicClient;
import jakarta.annotation.PostConstruct;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;

/** The indicator on the claims page: is the DuckClinic, which the fraud agent asks, reachable? */
@Named
@ViewScoped
public class FraudAgentBean implements Serializable {

    @Inject
    private ClinicClient clinic;
    private String       state = "warn";
    private String       text  = "";


    @PostConstruct
    public void check() {
        if ("off".equalsIgnoreCase(AppConfig.get("DUCK_FRAUD_AGENT", "on"))) {
            state = "warn";
            text  = "Fraud agent switched off (DUCK_FRAUD_AGENT=off): claims get the rule-based check";
            return;
        }
        ClinicClient.Status status = clinic.check();
        state = status.state();
        text  = status.state().equals("ok") ? status.text() : status.text() + ". Vet-care claims fall back to the rule-based check.";
    }

    public String getState() { return state; }
    public String getText() { return text; }
}
