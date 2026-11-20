package eu.hansolo.duckinsurance.rest;

import eu.hansolo.mcp.rest.McpResource;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;

import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.enums.SecuritySchemeType;
import org.eclipse.microprofile.openapi.annotations.info.Contact;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.security.SecurityScheme;

import java.util.Set;

/** REST API under /api. The MCP endpoint of the library is registered here too, so it is served at /api/mcp. Overriding getClasses() switches off scanning: every resource must be listed. */
@OpenAPIDefinition(info = @Info(title = "DuckInsurance API", version = "1.0", description = "Insurance for ducks: owners, ducks, policies, claims and vets. Log in with HTTP Basic (demo users: admin, adjuster, customer). The MCP endpoint /api/mcp is not part of this description.", contact = @Contact(name = "DuckInsurance demo")), security = @SecurityRequirement(name = "basicAuth"))
@SecurityScheme(securitySchemeName = "basicAuth", type = SecuritySchemeType.HTTP, scheme = "basic")
@ApplicationPath("/api")
public class RestApplication extends Application {

    @Override
    public Set<Class<?>> getClasses() {
        return Set.of(AgentResource.class, ClaimResource.class, CustomerResource.class, DuckResource.class, PolicyResource.class, StatsResource.class, VetResource.class, DomainExceptionMapper.class, McpResource.class);
    }
}
