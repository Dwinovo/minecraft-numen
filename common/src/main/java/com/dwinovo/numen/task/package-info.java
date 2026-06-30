/**
 * <strong>Public API.</strong> The world-action task contract. A world-action
 * tool returns a {@link TaskRecord} subclass; the pack pairs each record type
 * with the {@link CompanionTask} that runs it on the body via
 * {@link CompanionTaskFactory#register}. {@link TaskResult} / {@link TaskState}
 * model the outcome, and {@link PathTally} reports the terrain a task
 * incidentally changed while travelling.
 *
 * <p>Internal members of this package ({@link com.dwinovo.numen.api.Internal @Internal}):
 * {@link TaskQueue}, {@link CompanionTickDispatcher} and
 * {@link UnsupportedCompanionTask}.
 */
package com.dwinovo.numen.task;
