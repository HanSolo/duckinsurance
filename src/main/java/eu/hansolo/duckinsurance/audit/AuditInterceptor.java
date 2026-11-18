package eu.hansolo.duckinsurance.audit;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.security.enterprise.SecurityContext;

import java.security.Principal;

@Audited
@Interceptor
@Priority(Interceptor.Priority.APPLICATION + 10)
public class AuditInterceptor {

    @Inject
    private AuditLog        log;
    @Inject
    private SecurityContext securityContext;


    @AroundInvoke
    public Object audit(InvocationContext context) throws Exception {
        String operation = context.getMethod().getDeclaringClass().getSimpleName() + "." + context.getMethod().getName();
        try {
            Object result = context.proceed();
            log.record(caller(), operation + " ok");
            return result;
        } catch (Exception e) {
            log.record(caller(), operation + " FAILED: " + e.getMessage());
            throw e;
        }
    }

    private String caller() {
        Principal principal = securityContext.getCallerPrincipal();
        return principal == null ? "system" : principal.getName();
    }
}
