/**
 * <strong>Public API.</strong> The raw tool contract the engine schedules. A
 * tool implements {@link NumenTool} (name / description / schema / {@code invoke})
 * and is registered in the {@link ToolRegistry}; {@link ToolCall} is the handle
 * passed to {@link NumenTool#invoke} — the tool does whatever it likes (on any
 * thread, sending its own packets, calling out to anything) and calls
 * {@link ToolCall#complete} when the result is ready. The engine is a scheduler,
 * not an executor.
 *
 * <p>The ergonomic {@code @NumenAction} annotations and {@code ToolSchema} live
 * next door in {@code agent.tool.api}; the reflective adapter that turns an
 * annotated method into a {@link NumenTool}, and any server-side task execution,
 * ship in the tool pack ({@code numen-core}) — not in the engine.
 *
 * <p>Internal members ({@link com.dwinovo.numen.api.Internal @Internal}):
 * {@link ToolInvocation} (the scheduler's per-call unit) and
 * {@link ClientToolContext} (the client-side context implementation).
 */
package com.dwinovo.numen.agent.tool;
