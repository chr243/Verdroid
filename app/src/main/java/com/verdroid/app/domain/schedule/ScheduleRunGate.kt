package com.verdroid.app.domain.schedule

import com.verdroid.app.domain.model.PauseReason
import com.verdroid.app.domain.model.TaskStatus

/**
 * v1.0.59: decides whether a scheduled run may start in its chat (pure, unit-tested).
 *
 * Before, any QUEUED / PAUSED / RATE_LIMITED chat skipped the run. A brand-new dedicated chat starts out QUEUED
 * ("Queued", from createTask), so the very first run of every schedule was skipped, the chat stayed "Queued" forever
 * and blocked every later run too. Now only a run that is really going, or a quota pause that resumes on its own
 * shortly, holds the next run back; anything else (queued but never started, stopped, failed, a manual pause) is
 * superseded by the new run.
 */
object ScheduleRunGate {
    sealed interface Decision
    data class Skip(val reason: String) : Decision
    /** Start the run; [superseded] = a stuck earlier run is dropped first (its retry job cancelled, a note in the chat). */
    data class Start(val superseded: Boolean) : Decision

    /** A quota pause resuming within this window keeps the next run waiting (it would only hit the same limit). */
    const val WAIT_FOR_RESUME_MS = 60 * 60_000L

    fun decide(running: Boolean, status: TaskStatus?, pauseReason: String?, resumeAt: Long?, now: Long): Decision = when {
        running -> Skip("skipped: the previous run is still going")
        status == TaskStatus.RATE_LIMITED && PauseReason.autoResumes(pauseReason) && resumeAt != null && resumeAt > now &&
            resumeAt - now <= WAIT_FOR_RESUME_MS -> Skip("skipped: the previous run waits for the model quota and resumes on its own")
        status == TaskStatus.QUEUED || status == TaskStatus.PAUSED || status == TaskStatus.RATE_LIMITED -> Start(superseded = true)
        else -> Start(superseded = false)
    }
}
