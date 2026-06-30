/**
 * <strong>Public API.</strong> The {@code @NumenAction} authoring surface: a
 * tool pack declares a tool as an annotated method ({@link NumenAction}) with
 * {@link Arg}-annotated parameters. {@link ToolSchema} derives the OpenAI-style
 * JSON schema from the signature, and {@link ToolContext} carries the per-call
 * id / deadline basis into the method.
 *
 * <p>This is a stable contract for tool packs — treat changes here as semver.
 */
package com.dwinovo.numen.agent.tool.api;
