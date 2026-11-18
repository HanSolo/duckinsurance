package eu.hansolo.duckinsurance.config;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Named;
import jakarta.servlet.ServletContext;

/** Shown in the sidebar so the audience can see which runtime is serving the page. */
@Named
@ApplicationScoped
public class RuntimeInfo {

    public String getServer() {
        return ((ServletContext) FacesContext.getCurrentInstance().getExternalContext().getContext()).getServerInfo();
    }

    public String getJava() {
        return Runtime.version().toString();
    }
}
