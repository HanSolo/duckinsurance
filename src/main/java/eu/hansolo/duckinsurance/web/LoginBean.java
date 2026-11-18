package eu.hansolo.duckinsurance.web;

import jakarta.enterprise.context.RequestScoped;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.SecurityContext;
import jakarta.security.enterprise.authentication.mechanism.http.AuthenticationParameters;
import jakarta.security.enterprise.credential.Password;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

@Named
@RequestScoped
public class LoginBean {

    @Inject
    private SecurityContext securityContext;
    @Inject
    private FacesContext    facesContext;
    private String          username;
    private String          password;


    public void login() throws IOException {
        ExternalContext external = facesContext.getExternalContext();
        HttpServletResponse response = (HttpServletResponse) external.getResponse();
        AuthenticationParameters parameters = AuthenticationParameters.withParams().credential(new UsernamePasswordCredential(username, new Password(password)));
        AuthenticationStatus status = securityContext.authenticate((HttpServletRequest) external.getRequest(), response, parameters);
        if (status == AuthenticationStatus.SUCCESS) {
            external.redirect(external.getRequestContextPath() + "/app/dashboard.xhtml");
        } else if (status == AuthenticationStatus.SEND_FAILURE) {
            response.setStatus(HttpServletResponse.SC_OK);
            Messages.error("Invalid username or password");
        } else {
            facesContext.responseComplete();
        }
    }

    public void logout() throws IOException, ServletException {
        ExternalContext external = facesContext.getExternalContext();
        ((HttpServletRequest) external.getRequest()).logout();
        external.invalidateSession();
        external.redirect(external.getRequestContextPath() + "/login.xhtml");
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
}
