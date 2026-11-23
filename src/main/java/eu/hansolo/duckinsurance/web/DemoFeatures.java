package eu.hansolo.duckinsurance.web;

import eu.hansolo.duckinsurance.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.faces.event.ComponentSystemEvent;
import jakarta.inject.Named;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;


/**
 * Three switches for telling the story of the demo step by step: a plain Jakarta EE application first, then the Assistant, then the fraud agent,
 * then the agent-to-agent part. The state is the same for every user and browser and changes at once; only an ADMIN can switch.
 * <p>
 * Start values (all on when not set, which is the normal behaviour of the application): DUCK_ASSISTANT, DUCK_FRAUD_AGENT and DUCK_AGENTS_TAB,
 * each "on" or "off". A restart goes back to these values.
 */
@Named
@ApplicationScoped
public class DemoFeatures {
    private static final Logger        LOG         = Logger.getLogger(DemoFeatures.class.getName());
    private static final AtomicBoolean ASSISTANT   = new AtomicBoolean(start("DUCK_ASSISTANT"));
    private static final AtomicBoolean FRAUD_AGENT = new AtomicBoolean(start("DUCK_FRAUD_AGENT"));
    private static final AtomicBoolean AGENTS_TAB  = new AtomicBoolean(start("DUCK_AGENTS_TAB"));


    /** Used outside the web layer (AgentCheck), so it is static. */
    public static boolean fraudAgentOn() { return FRAUD_AGENT.get(); }

    public boolean isAssistant()  { return ASSISTANT.get(); }
    public boolean isFraudAgent() { return FRAUD_AGENT.get(); }
    public boolean isAgentsTab()  { return AGENTS_TAB.get(); }

    /** Each toggle reloads the current page (GET), so the menu changes and a page that was switched off sends the browser to the dashboard. */
    public String toggleAssistant()  { toggle(ASSISTANT, "Assistant"); return reload(); }
    public String toggleFraudAgent() { toggle(FRAUD_AGENT, "Fraud agent"); return reload(); }
    public String toggleAgentsTab()  { toggle(AGENTS_TAB, "Agents tab"); return reload(); }

    /** Called by the layout before every page (preRenderView): a page that is switched off sends the browser to the dashboard. */
    public void guard(final ComponentSystemEvent event) throws IOException {
        String viewId = FacesContext.getCurrentInstance().getViewRoot().getViewId();
        boolean on = switch (viewId) {
            case "/app/chat.xhtml"   -> isAssistant();
            case "/app/agents.xhtml" -> isAgentsTab();
            default                  -> true;
        };
        if (!on) {
            FacesContext context = FacesContext.getCurrentInstance();
            context.getExternalContext().redirect(context.getExternalContext().getRequestContextPath() + "/app/dashboard.xhtml");
            context.responseComplete();
        }
    }


    private static String reload() {
        return FacesContext.getCurrentInstance().getViewRoot().getViewId() + "?faces-redirect=true&includeViewParams=true";
    }

    private static void toggle(final AtomicBoolean flag, final String name) {
        FacesContext context = FacesContext.getCurrentInstance();
        if (context == null || !context.getExternalContext().isUserInRole("ADMIN")) { return; }
        boolean now = !flag.get();
        flag.set(now);
        LOG.info("Demo switch: " + name + " " + (now ? "on" : "off") + " (by " + context.getExternalContext().getRemoteUser() + ")");
    }

    private static boolean start(final String name) {
        return !"off".equalsIgnoreCase(AppConfig.get(name, "on"));
    }
}
