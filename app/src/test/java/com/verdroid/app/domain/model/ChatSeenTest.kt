package com.verdroid.app.domain.model

import org.junit.Assert.*
import org.junit.Test

class ChatSeenTest {
    @Test fun openChatClearsUnreadEvenWhenTheWindowIsFull() {
        // v1.0.60-test2: the windowed list kept its size, so new replies stayed unread on the chat head.
        assertTrue(ChatSeen.shouldMark(resumed = true, transitioning = false, unread = 3, hasMessages = true))
        assertTrue(ChatSeen.shouldMark(resumed = true, transitioning = false, unread = 0, hasMessages = true))
    }

    @Test fun notWhileHiddenOrAnimating() {
        assertFalse(ChatSeen.shouldMark(resumed = false, transitioning = false, unread = 3, hasMessages = true))
        assertFalse(ChatSeen.shouldMark(resumed = true, transitioning = true, unread = 3, hasMessages = true))
        assertFalse(ChatSeen.shouldMark(resumed = true, transitioning = false, unread = 0, hasMessages = false))
    }
}
