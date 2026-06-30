/**
 * <strong>Public API.</strong> Tool registration and the tool contract. A tool
 * pack turns a {@link com.dwinovo.numen.agent.tool.api.NumenAction}-annotated
 * method into a {@link NumenTool} via {@link NumenTools#tool} and registers it
 * in the {@link ToolRegistry}; {@link ToolCall} is the handle passed to
 * {@link NumenTool#invoke}. {@link NumenActionTool} is the reflective adapter
 * those factories produce.
 *
 * <p>Internal members of this package ({@link com.dwinovo.numen.api.Internal @Internal}):
 * {@link ToolInvocation} (the dispatcher's per-call unit) and
 * {@link ClientToolContext} (the client-side context implementation).
 */
package com.dwinovo.numen.agent.tool;
