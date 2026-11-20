package eu.hansolo.duckinsurance.security;

import eu.hansolo.duckinsurance.config.AppConfig;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.Set;


/**
 * Lets the agents of the two clinics call the MCP tools of the insurer. They send "Authorization: Bearer clinic-agent:SECRET" or "therapy-agent:SECRET" and get the role AGENT, which can use the partner tools and nothing else of the back office.
 * The secret comes from AGENT_SECRET (demo default below). A shared secret in an environment variable is a demo simplification, not a production design.
 */
@ApplicationScoped
public class AgentAccess {
    private static final Set<String> AGENTS = Set.of("clinic-agent", "therapy-agent");


    public Optional<InternalTokens.Identity> verify(String token) {
        int colon = token == null ? -1 : token.indexOf(':');
        if (colon < 1) return Optional.empty();
        String agent = token.substring(0, colon);
        String secret = AppConfig.get("AGENT_SECRET", "duck-agent-demo");
        if (!AGENTS.contains(agent) || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), token.substring(colon + 1).getBytes(StandardCharsets.UTF_8))) return Optional.empty();
        return Optional.of(new InternalTokens.Identity(agent, Set.of("AGENT")));
    }
}
