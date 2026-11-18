package eu.hansolo.duckinsurance.chat;

import eu.hansolo.duckinsurance.security.InternalTokens;
import eu.hansolo.mcp.client.Elicitation;
import eu.hansolo.mcp.client.McpClient;
import jakarta.annotation.PostConstruct;
import jakarta.faces.context.ExternalContext;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.Serializable;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The assistant page. A question runs on a background thread: the model (Ollama by default) gets the tools of this application's own MCP endpoint, read through the
 * MCP client library, and its text streams in while it is written. The page polls {@link #poll()} for the state. The client calls /api/mcp with a short-lived signed token
 * for the logged-in user, so the tools run with that user's role, and tools that change data ask the user to confirm (MCP elicitation) before they act.
 */
@Named
@ViewScoped
public class ChatBean implements Serializable {

    /** One line of the conversation, with the tool calls that led to an answer. Plain class with getters, so it works in any EL version. */
    public static class Line implements Serializable {
        private final String       role;
        private final String       text;
        private final List<String> tools;


        public Line(String role, String text, List<String> tools) {
            this.role = role;
            this.text = text;
            this.tools = tools;
        }


        public String getRole() { return role; }
        public String getText() { return text; }
        public List<String> getTools() { return tools; }
    }

    /** What the background thread needs from the web request, captured on the request thread. */
    private record Context(URI endpoint, String user, Set<String> groups) {
    }

    @Inject
    private       InternalTokens tokens;
    private final List<Line>     lines      = new ArrayList<>();
    private final List<String[]> history    = new ArrayList<>();
    private       String         input;
    private       String         modelState = "warn";
    private       String         modelText  = "";
    private       String         mcpState   = "warn";
    private       String         mcpText    = "";
    private transient ChatRun    run;


    /** Checks the model server and the MCP endpoint; runs when the page opens and on "Check again". */
    @PostConstruct
    public void check() {
        ModelCheck.Result model = ModelCheck.run();
        modelState = model.state();
        modelText = model.text();
        try {
            int count = client(context(), null).tools().size();
            mcpState = "ok";
            mcpText = "MCP endpoint reachable, " + count + " tools";
        } catch (RuntimeException e) {
            mcpState = "bad";
            mcpText = "MCP endpoint not usable: " + e.getMessage();
        }
    }

    public void send() {
        String question = input == null ? "" : input.trim();
        if (question.isEmpty() || run != null) return;
        input = null;
        lines.add(new Line("user", question, List.of()));
        Context          context = context();
        List<JsonObject> earlier = historyAsJson();
        ChatRun          current = new ChatRun(question);
        run = current;
        Thread.ofVirtual().name("duck-chat").start(() -> {
            try {
                AgentLoop loop = new AgentLoop(LlmSettings.create(), new McpToolBackend(client(context, current::ask)), LlmSettings.maxRounds());
                current.finish(loop.run(earlier, question, current));
            } catch (RuntimeException e) {
                current.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
    }

    /** Called by the page every moment while a question is running: moves a finished run into the conversation. */
    public void poll() {
        ChatRun current = run;
        if (current == null || !current.isDone()) return;
        run = null;
        if (current.isCancelled()) {
            lines.add(new Line("error", "Stopped.", List.of()));
        } else if (current.error() != null) {
            lines.add(new Line("error", "The assistant could not answer: " + current.error(), List.of()));
        } else {
            AgentLoop.Answer answer = current.answer();
            List<String> tools = answer.calls().stream().map(c -> c.name() + c.arguments() + (c.isError() ? " failed: " : " -> ") + shorten(c.result())).toList();
            String text = answer.text().isBlank() ? "(no answer)" : answer.text();
            lines.add(new Line("assistant", text, tools));
            history.add(new String[]{"user", current.question()});
            history.add(new String[]{"assistant", text});
        }
    }

    public void confirm() {
        if (run != null) run.respond(true);
    }

    public void decline() {
        if (run != null) run.respond(false);
    }

    public void stop() {
        if (run != null) run.cancel();
    }

    public void clear() {
        stop();
        run = null;
        lines.clear();
        history.clear();
    }

    private Context context() {
        ExternalContext external = FacesContext.getCurrentInstance().getExternalContext();
        Set<String>     groups   = new LinkedHashSet<>();
        for (String role : List.of("ADMIN", "ADJUSTER", "CUSTOMER")) {
            if (external.isUserInRole(role)) groups.add(role);
        }
        String own = System.getenv("MCP_SELF_URL");
        return new Context(URI.create(own == null || own.isBlank() ? "http://localhost:8080/api/mcp" : own.trim()), external.getRemoteUser(), groups);
    }

    /** A client for this application's MCP endpoint with a short-lived signed token of the user, so the user's role applies to every tool call. */
    private McpClient client(Context context, Elicitation.Handler confirmation) {
        McpClient.Builder builder = McpClient.builder(context.endpoint()).bearerToken(() -> tokens.issue(context.user(), context.groups())).clientInfo("duckinsurance-chat", "1.0").timeout(Duration.ofSeconds(30));
        if (confirmation != null) builder.elicitation(confirmation, Elicitation.Mode.FORM);
        return builder.build();
    }

    private List<JsonObject> historyAsJson() {
        return history.stream().map(h -> Json.createObjectBuilder().add("role", h[0]).add("content", h[1]).build()).toList();
    }

    private static String shorten(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() > 220 ? flat.substring(0, 220) + "..." : flat;
    }

    public boolean isRunning() { return run != null; }
    public String getLive() { return run == null ? "" : run.live(); }
    public List<String> getLiveTools() { return run == null ? List.of() : run.tools(); }
    public String getPendingMessage() { return run == null || run.pending() == null ? null : run.pending().message(); }
    public String getModelState() { return modelState; }
    public String getModelText() { return modelText; }
    public String getMcpState() { return mcpState; }
    public String getMcpText() { return mcpText; }
    public String getModel() { return LlmSettings.model(); }
    public List<Line> getLines() { return lines; }
    public String getInput() { return input; }
    public void setInput(String input) { this.input = input; }
}
