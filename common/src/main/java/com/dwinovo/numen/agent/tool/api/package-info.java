/**
 * <strong>Public API.</strong> {@link ToolContext} — the per-call context (the
 * tool-call id plus a deadline helper) a server-side tool uses when building a
 * task record.
 *
 * <p>The reflective {@code @NumenAction} / {@code @Arg} authoring layer that
 * used to live here has been removed: a tool is just a
 * {@link com.dwinovo.numen.agent.tool.NumenTool} (name, description, schema,
 * {@code invoke}). numen-core provides optional authoring sugar (a {@code Schema}
 * builder and a {@code ServerNumenTool} base) for packs that want it.
 */
package com.dwinovo.numen.agent.tool.api;
