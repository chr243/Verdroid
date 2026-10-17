package com.verdroid.app.domain.schedule

import com.verdroid.app.agent.AgentLoop
import com.verdroid.app.domain.model.ChatMessage
import com.verdroid.app.domain.model.MessageKind
import com.verdroid.app.domain.model.MessageRole
import com.verdroid.app.domain.model.PauseReason
import com.verdroid.app.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.verdroid.app.data.schedule.ScheduledPrompt
import org.junit.Test

/** Regression for v1.0.58: scheduled runs stuck as "Queued" and a stale/invented prompt in the scheduled chat. */
class ScheduleRunGateTest {
    private val now = 1_000_000_000L
    private fun d(running: Boolean = false, status: TaskStatus?, reason: String? = null, resumeAt: Long? = null) =
        ScheduleRunGate.decide(running, status, reason, resumeAt, now)

    @Test fun queuedNeverStartedChatDoesNotBlock() {
        // The v1.0.58 bug: a fresh chat is QUEUED ("Queued") and every run was skipped.
        assertEquals(ScheduleRunGate.Start(superseded = true), d(status = TaskStatus.QUEUED))
    }

    @Test fun idleCompletedFailedOrNewStartNormally() {
        listOf(null, TaskStatus.IDLE, TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED).forEach {
            assertEquals(it.toString(), ScheduleRunGate.Start(superseded = false), d(status = it))
        }
    }

    @Test fun stuckPausesAreSuperseded() {
        assertEquals(ScheduleRunGate.Start(true), d(status = TaskStatus.PAUSED, reason = null))
        // quota pause far in the future, or already overdue (its job never came): superseded
        val q = PauseReason.values().first { it.autoResume }.name
        assertEquals(ScheduleRunGate.Start(true), d(status = TaskStatus.RATE_LIMITED, reason = q, resumeAt = now + 5 * 3_600_000L))
        assertEquals(ScheduleRunGate.Start(true), d(status = TaskStatus.RATE_LIMITED, reason = q, resumeAt = now - 60_000L))
    }

    @Test fun onlyARealRunOrAnImminentQuotaResumeSkips() {
        assertTrue(d(running = true, status = TaskStatus.RUNNING) is ScheduleRunGate.Skip)
        assertTrue(d(running = true, status = TaskStatus.QUEUED) is ScheduleRunGate.Skip)
        val q = PauseReason.values().first { it.autoResume }.name
        assertTrue(d(status = TaskStatus.RATE_LIMITED, reason = q, resumeAt = now + 10 * 60_000L) is ScheduleRunGate.Skip)
    }

    private fun msg(role: MessageRole, kind: MessageKind) =
        ChatMessage(0, 1, role, "x", kind, null, null, null, null, false, null, now)

    @Test fun chatWithoutAUserTurnNeverCallsTheModel() {
        assertFalse(AgentLoop.hasUserTurn(emptyList()))
        assertFalse(AgentLoop.hasUserTurn(listOf(msg(MessageRole.SYSTEM, MessageKind.STATUS))))
        assertTrue(AgentLoop.hasUserTurn(listOf(msg(MessageRole.USER, MessageKind.SCHEDULED_PROMPT))))
        assertTrue(AgentLoop.hasUserTurn(listOf(msg(MessageRole.USER, MessageKind.NORMAL))))
        // an empty user prompt is no prompt
        assertFalse(AgentLoop.hasUserTurn(listOf(msg(MessageRole.USER, MessageKind.SCHEDULED_PROMPT).copy(content = "  "))))
    }

    @Test fun sharedChatNeverReplacesTheUsersOwnWork() {
        val q = PauseReason.values().first { it.autoResume }.name
        val shared = { running: Boolean, st: TaskStatus?, r: String? -> ScheduleRunGate.decide(running, st, r, now + 5 * 3_600_000L, now, shared = true) }
        assertTrue(shared(true, TaskStatus.RUNNING, null) is ScheduleRunGate.Skip)
        assertTrue(shared(false, TaskStatus.RATE_LIMITED, q) is ScheduleRunGate.Skip) // the user's run resumes on its own
        assertEquals(ScheduleRunGate.Start(false), shared(false, TaskStatus.COMPLETED, null)) // normal history: just append
        assertEquals(ScheduleRunGate.Start(true), shared(false, TaskStatus.QUEUED, null))
    }

    @Test fun scheduledPromptAfterNormalHistoryIsTheLatestUserTurn() {
        val hist = listOf(msg(MessageRole.USER, MessageKind.NORMAL).copy(id = 1, content = "hi"), msg(MessageRole.ASSISTANT, MessageKind.NORMAL).copy(id = 2),
            msg(MessageRole.USER, MessageKind.SCHEDULED_PROMPT).copy(id = 3, content = ScheduledPrompt.build("CNN", "check cnn", false, "10:00")))
        assertTrue(AgentLoop.hasUserTurn(hist))
        val last = hist.last { it.role == MessageRole.USER }
        assertEquals("CNN", ScheduleSafety.scheduleName(last.content)) // unattended while this is the latest user turn
        // the user typing afterwards ends unattended mode
        val after = hist + msg(MessageRole.USER, MessageKind.NORMAL).copy(id = 4, content = "thanks")
        assertEquals(null, ScheduleSafety.scheduleName(after.last { it.role == MessageRole.USER }.content))
    }
}
