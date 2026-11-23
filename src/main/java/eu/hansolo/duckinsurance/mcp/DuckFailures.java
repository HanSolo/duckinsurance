package eu.hansolo.duckinsurance.mcp;

import eu.hansolo.duckinsurance.domain.DomainException;
import eu.hansolo.mcp.api.ToolCall;
import eu.hansolo.mcp.api.ToolExceptionMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Decides what the model is told when an MCP tool throws. Business errors ({@link DomainException}) show their message, so the model can react to them.
 * An unexpected error in {@code request_preauthorization} is explained with its type and root cause, because the caller is another agent that has to say what went wrong.
 * Everything else is left to the library, which logs it and answers {@code Tool execution failed}.
 */
@ApplicationScoped
public class DuckFailures implements ToolExceptionMapper {
    private static final Logger LOG = Logger.getLogger(DuckFailures.class.getName());


    @Override
    public Optional<String> message(Throwable failure, ToolCall call) {
        if (failure instanceof DomainException) {
            return Optional.ofNullable(failure.getMessage());
        }
        if ("request_preauthorization".equals(call.name()) && failure instanceof RuntimeException) {
            LOG.log(Level.WARNING, "request_preauthorization failed unexpectedly", failure);
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return Optional.of("Unexpected error in request_preauthorization: " + failure.getClass().getSimpleName() + ": " + failure.getMessage() + (root == failure ? "" : " (cause: " + root.getClass().getSimpleName() + ": " + root.getMessage() + ")"));
        }
        return Optional.empty();
    }
}
