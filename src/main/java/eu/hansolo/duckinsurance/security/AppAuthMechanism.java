package eu.hansolo.duckinsurance.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.security.enterprise.AuthenticationStatus;
import jakarta.security.enterprise.authentication.mechanism.http.AutoApplySession;
import jakarta.security.enterprise.authentication.mechanism.http.HttpAuthenticationMechanism;
import jakarta.security.enterprise.authentication.mechanism.http.HttpMessageContext;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.Password;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStoreHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * One mechanism, two audiences: the Faces UI logs in programmatically (session cookie afterwards),
 * while REST clients (curl, an MCP server, ...) send HTTP Basic credentials.
 */
@ApplicationScoped
@AutoApplySession
public class AppAuthMechanism implements HttpAuthenticationMechanism {

    @Inject
    private IdentityStoreHandler identityStoreHandler;
    @Inject
    private InternalTokens       tokens;
    @Inject
    private AgentAccess          agents;


    @Override
    public AuthenticationStatus validateRequest(HttpServletRequest request, HttpServletResponse response, HttpMessageContext context) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6)) {
            String[] parts = new String(Base64.getDecoder().decode(header.substring(6).trim()), StandardCharsets.UTF_8).split(":", 2);
            return authenticate(context, new UsernamePasswordCredential(parts[0], new Password(parts.length > 1 ? parts[1] : "")));
        }
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            // the application's own tokens (see InternalTokens), used by the internal MCP client of the Assistant page, and the partner agents (see AgentAccess)
            return tokens.verify(header.substring(7).trim()).or(() -> agents.verify(header.substring(7).trim())).map(identity -> context.notifyContainerAboutLogin(identity.user(), identity.groups())).orElseGet(context::responseUnauthorized);
        }
        if (context.isAuthenticationRequest()) {
            return authenticate(context, context.getAuthParameters().getCredential());
        }
        if (context.isProtected()) {
            if (request.getRequestURI().startsWith(request.getContextPath() + "/api/")) {
                response.setHeader("WWW-Authenticate", "Basic realm=\"duckinsurance\"");
                return context.responseUnauthorized();
            }
            return context.redirect(request.getContextPath() + "/login.xhtml");
        }
        return context.doNothing();
    }

    private AuthenticationStatus authenticate(HttpMessageContext context, Credential credential) {
        CredentialValidationResult result = identityStoreHandler.validate(credential);
        return result.getStatus() == CredentialValidationResult.Status.VALID ? context.notifyContainerAboutLogin(result) : context.responseUnauthorized();
    }
}
