package com.dwinovo.numen.agent.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.function.Consumer;

/**
 * One in-flight tool call handed to {@link NumenTool#invoke}. It is the entire
 * surface a tool needs to run and report back — the agent loop hands the tool
 * this object and then forgets about <em>how</em> the work happens. A tool
 * reports its result through exactly one of two verbs:
 *
 * <ul>
 *   <li>{@link #complete(String)} — "here is the result." The one verb: a tool
 *       calls it whenever and from wherever its result is ready — synchronously
 *       on the agent (client) thread, or later after handing the work to the
 *       server body (the result then arrives via {@code TaskResultPayload} and
 *       the loop completes the call).</li>
 * </ul>
 *
 * The agent loop neither knows nor cares <em>how</em> a tool finishes — that
 * choice belongs entirely to the tool. This is the seam that makes tool execution
 * transparent to {@code EntityAgentLoop}: it dispatches a call and is notified of
 * the result, with no branch on tool category.
 *
 * <p>The engine is a scheduler, not an executor: there is exactly one verb,
 * {@link #complete(String)}. A tool does whatever it likes inside
 * {@link NumenTool#invoke} — return immediately, hop a thread, send its own
 * packets to the server body, proxy to an external service, hook a chat app — on
 * any thread, then calls {@link #complete} when the result is ready (the
 * scheduler waits, with only a backstop timeout). How and where the work happens
 * is none of the engine's business.
 */
public final class ToolCall {

    private final String id;
    private final String toolName;
    private final String rawArgs;
    private final ClientToolContext ctx;
    private final Consumer<String> completion;   // the single "done" sink

    public ToolCall(String id, String toolName, String rawArgs, ClientToolContext ctx,
                    Consumer<String> completion) {
        this.id = id;
        this.toolName = toolName;
        this.rawArgs = rawArgs;
        this.ctx = ctx;
        this.completion = completion;
    }

    /** The LLM's {@code tool_call} id — carried through to the matching result message. */
    public String id() { return id; }

    public String toolName() { return toolName; }

    /** Live entity / world handle for client-side tools. Entity may be {@code null} (out of view). */
    public ClientToolContext ctx() { return ctx; }

    /** Raw argument JSON string exactly as the model emitted it. */
    public String rawArgs() { return rawArgs; }

    /**
     * Parsed arguments. Throws {@link IllegalArgumentException} on malformed
     * JSON; {@link NumenTool#invoke} catches it and reports a failed result so
     * the conversation continues.
     */
    public JsonObject args() {
        if (rawArgs == null || rawArgs.isBlank()) return new JsonObject();
        try {
            return JsonParser.parseString(rawArgs).getAsJsonObject();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("invalid arguments JSON: " + ex.getMessage());
        }
    }

    /** The one verb: deliver the result, whenever and from wherever the tool is ready. */
    public void complete(String resultJson) {
        completion.accept(resultJson);
    }
}
