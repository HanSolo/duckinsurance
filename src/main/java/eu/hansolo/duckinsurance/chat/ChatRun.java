package eu.hansolo.duckinsurance.chat;

import eu.hansolo.mcp.client.Elicitation;
import jakarta.json.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One question being answered on a background thread. The page reads this state when it polls: the text written so far, the tool calls,
 * and a pending confirmation if a tool asked the user. Everything here is safe to read from another thread.
 */
final class ChatRun implements AgentLoop.Listener {

    /** A question from a tool to the user, waiting for the answer from the page. */
    static final class Pending {
        private final String                                message;
        private final CompletableFuture<Elicitation.Response> answer = new CompletableFuture<>();


        private Pending(final String message) {
            this.message = message;
        }


        String message() { return message; }
    }

    private final String              question;
    private final StringBuilder       live  = new StringBuilder();
    private final List<String>        tools = new ArrayList<>();
    private volatile boolean          done;
    private volatile boolean          cancelled;
    private volatile String           error;
    private volatile AgentLoop.Answer answer;
    private volatile Pending          pending;


    ChatRun(final String question) {
        this.question = question;
    }


    @Override public synchronized void round(final int number) {
        live.setLength(0);
    }

    @Override public synchronized void text(final String delta) {
        if (cancelled) { throw new CancellationException("stopped by the user"); }
        live.append(delta);
    }

    @Override public void toolCalled(final String name, final JsonObject arguments) {
        synchronized (tools) { tools.add("→ " + name + arguments); }
    }

    @Override public void toolReturned(final AgentLoop.Call call) {
        String result = call.result().replaceAll("\\s+", " ");
        synchronized (tools) { tools.add((call.isError() ? "✗ " : "← ") + (result.length() > 220 ? result.substring(0, 220) + "..." : result)); }
    }

    @Override public boolean cancelled() {
        return cancelled;
    }

    /** The handler for the MCP client: shows the question on the page and waits for the user (three minutes at most). */
    Elicitation.Response ask(final Elicitation.Request request) {
        Pending question = new Pending(request.message());
        pending = question;
        try {
            return question.answer.get(180, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException e) {
            return Elicitation.Response.cancel();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Elicitation.Response.cancel();
        } finally {
            pending = null;
        }
    }

    void respond(final boolean accept) {
        Pending question = pending;
        if (question != null) { question.answer.complete(accept ? Elicitation.Response.accept() : Elicitation.Response.decline()); }
    }

    void cancel() {
        cancelled = true;
        respond(false);
    }

    void finish(final AgentLoop.Answer result) {
        answer = result;
        done   = true;
    }

    void fail(final String message) {
        error = message;
        done  = true;
    }

    String question() { return question; }
    synchronized String live() { return live.toString(); }
    List<String> tools() { synchronized (tools) { return List.copyOf(tools); } }
    boolean isDone() { return done; }
    boolean isCancelled() { return cancelled; }
    String error() { return error; }
    AgentLoop.Answer answer() { return answer; }
    Pending pending() { return pending; }
}
