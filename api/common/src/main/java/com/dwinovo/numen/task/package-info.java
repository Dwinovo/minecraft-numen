/**
 * <strong>Public API — the task contract a tool pack builds on.</strong>
 * <ul>
 *   <li>{@link TaskRecord} / {@link TaskState} / {@link TaskResult} — the typed
 *       envelope a world-action tool emits, its lifecycle states, and the result
 *       the agent loop reads back;</li>
 *   <li>{@link CompanionTask} / {@link Suspendable} — the executor a pack
 *       implements per record type;</li>
 *   <li>{@link CompanionTaskFactory} / {@link TaskDispatch} /
 *       {@link CompanionTickDispatcher} — the registration and driving points a
 *       pack (and its loader entry points) call;</li>
 *   <li>{@link TaskChain} / {@link BrainChains} / {@link BodyLog} — the
 *       priority-bidding chain surface for instinct layers;</li>
 *   <li>{@link com.dwinovo.numen.task.reflex reflex} — the instinct switch
 *       roster;</li>
 *   <li>{@link TaskStatusTool} / {@link TaskStopTool} — the two engine-owned
 *       tools a pack registers alongside its own.</li>
 * </ul>
 *
 * <p>Everything else in this package — the scheduler's own machinery
 * ({@code CompanionBrain}, {@code TaskQueue}, {@code ChainScheduler},
 * {@code LlmTaskChain}, {@code TaskSessionHooks}, {@code HandPinRelease},
 * {@code chain/}) — is internal and excluded from the {@code :api} jar. The
 * engine schedules; how a tool actually does its work (packet transport,
 * multi-tick driving) lives in the tool pack.
 */
package com.dwinovo.numen.task;
