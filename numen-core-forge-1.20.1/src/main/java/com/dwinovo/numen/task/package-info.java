/**
 * <strong>Public API.</strong> {@link TaskResult} — the result envelope a tool
 * hands back to the agent loop (serialised as the {@code role:tool} message the
 * LLM reads). The scheduler also uses it to report its own failures (unknown
 * tool, backstop timeout).
 *
 * <p>The engine is a scheduler, not a task executor: how a tool actually does
 * its work — any server-side task queue, packet transport, multi-tick driving —
 * lives in the tool pack ({@code numen-core}), not here.
 */
package com.dwinovo.numen.task;
