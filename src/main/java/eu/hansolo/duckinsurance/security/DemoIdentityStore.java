package eu.hansolo.duckinsurance.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.security.enterprise.credential.Credential;
import jakarta.security.enterprise.credential.UsernamePasswordCredential;
import jakarta.security.enterprise.identitystore.CredentialValidationResult;
import jakarta.security.enterprise.identitystore.IdentityStore;

import java.util.Map;
import java.util.Set;

/** Demo users only: plain-text passwords in code are for demonstration, never for production. */
@ApplicationScoped
public class DemoIdentityStore implements IdentityStore {

    private record Account(String password, String role) {
    }

    private static final Map<String, Account> ACCOUNTS = Map.of(
            "admin", new Account("admin", "ADMIN"),
            "adjuster", new Account("adjuster", "ADJUSTER"),
            "customer", new Account("customer", "CUSTOMER"));


    @Override
    public CredentialValidationResult validate(Credential credential) {
        if (credential instanceof UsernamePasswordCredential login) {
            Account account = ACCOUNTS.get(login.getCaller());
            if (account != null && account.password().equals(login.getPasswordAsString())) {
                return new CredentialValidationResult(login.getCaller(), Set.of(account.role()));
            }
            return CredentialValidationResult.INVALID_RESULT;
        }
        return CredentialValidationResult.NOT_VALIDATED_RESULT;
    }
}
