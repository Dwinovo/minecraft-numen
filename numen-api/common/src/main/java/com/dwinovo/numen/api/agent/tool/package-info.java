/**
 * <strong>Public API.</strong> The raw tool contract Numen API schedules. A
 * tool implements {@link NumenTool} (name / description / schema / {@code invoke})
 * and is registered in the {@link ToolRegistry}; {@link ToolCall} is the handle
 * passed to {@link NumenTool#invoke} — the tool does whatever it likes (on any
 * thread, sending its own packets, calling out to anything) and calls
 * {@link ToolCall#complete} when the result is ready. Numen API is a scheduler,
 * not an executor.
 *
 * <p>Also public: {@link Schema} (the JSON-schema builder every tool's
 * {@code parameterSchema} uses). The per-call {@code ToolContext} lives next door in
 * {@code agent.tool.api}; any server-side task execution ships in the tool pack
 * (Numen) — not in Numen API.
 */
package com.dwinovo.numen.api.agent.tool;
