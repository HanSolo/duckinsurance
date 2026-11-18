package eu.hansolo.duckinsurance.security;

import jakarta.enterprise.context.ApplicationScoped;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Short-lived, signed bearer tokens that the application hands to its own internal MCP client, so that the assistant acts as the logged-in user without knowing a password.
 * HMAC-SHA256 with a random key per process, valid for two minutes. A token only ever carries the identity and roles its user already had.
 */
@ApplicationScoped
public class InternalTokens {

    public record Identity(String user, Set<String> groups) {
    }

    private static final long TTL_SECONDS = 120;

    private final byte[] key = new byte[32];


    public InternalTokens() {
        new SecureRandom().nextBytes(key);
    }


    public String issue(String user, Set<String> groups) {
        String payload = user + "|" + String.join(",", groups) + "|" + (Instant.now().getEpochSecond() + TTL_SECONDS);
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return body + "." + sign(body);
    }

    public Optional<Identity> verify(String token) {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot < 1) return Optional.empty();
        String body = token.substring(0, dot);
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), token.substring(dot + 1).getBytes(StandardCharsets.UTF_8))) return Optional.empty();
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || Instant.now().getEpochSecond() > Long.parseLong(parts[2])) return Optional.empty();
            Set<String> groups = new LinkedHashSet<>(parts[1].isEmpty() ? Set.of() : Arrays.asList(parts[1].split(",")));
            return Optional.of(new Identity(parts[0], groups));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
