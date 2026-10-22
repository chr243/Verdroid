package com.verdroid.app.data.profiles

import com.verdroid.app.data.memory.Memory
import com.verdroid.app.data.memory.MemoryText
import org.junit.Assert.*
import org.junit.Test

class ProfilesTest {
    private val coding = Profile("p1", "Coding", "💻", model = "kilo:x", toolGroupsOff = setOf("twitter"), skills = setOf("a"),
        instructions = "Be terse.", separateMemory = true)

    @Test fun `default always exists first and survives broken data`() {
        assertEquals(listOf(Profiles.DEFAULT), Profiles.decode(null))
        assertEquals(listOf(Profiles.DEFAULT), Profiles.decode("not json"))
        val l = Profiles.decode(Profiles.encode(listOf(coding)))
        assertEquals(listOf(Profiles.DEFAULT_ID, "p1"), l.map { it.id })
        assertEquals(coding, l[1])
        assertFalse(Profiles.visible(listOf(Profiles.DEFAULT))); assertTrue(Profiles.visible(l))
    }

    @Test fun `unknown profile resolves to default and default cannot be deleted`() {
        val l = listOf(Profiles.DEFAULT, coding)
        assertEquals(Profiles.DEFAULT, Profiles.resolve(l, "gone"))
        assertEquals(l, Profiles.delete(l, Profiles.DEFAULT_ID))
        assertEquals(listOf(Profiles.DEFAULT), Profiles.delete(l, "p1"))
    }

    @Test fun `default changes nothing`() {
        val d = Profiles.DEFAULT
        assertTrue(Profiles.toolAllowed("twitter", null, d, true)); assertFalse(Profiles.toolAllowed("twitter", null, d, false))
        assertTrue(Profiles.skillAllowed(d, "any")); assertNull(Profiles.memoryScope(d))
        assertEquals("", Profiles.promptBlock(d, listOf(d)))
    }

    @Test fun `tool rule is chat override then profile then settings`() {
        assertFalse(Profiles.toolAllowed("twitter", null, coding, true))
        assertTrue(Profiles.toolAllowed("twitter", true, coding, false))
        assertFalse(Profiles.toolAllowed("web", false, coding, true))
        assertTrue(Profiles.toolAllowed("web", null, coding, true))
    }

    @Test fun `skills, memory scope, prompt and copy`() {
        assertTrue(Profiles.skillAllowed(coding, "a")); assertFalse(Profiles.skillAllowed(coding, "b"))
        assertEquals("p1", Profiles.memoryScope(coding)); assertNull(Profiles.memoryScope(coding.copy(separateMemory = false)))
        val block = Profiles.promptBlock(coding, listOf(Profiles.DEFAULT, coding))
        assertTrue("Coding" in block && "Be terse." in block)
        val copy = Profiles.copyOf(coding, "p2", "New")
        assertEquals(coding.copy(id = "p2", name = "New"), copy)
        assertEquals(2, Profiles.templates { "t_$it" }.size)
        assertNotEquals("p5", Profiles.newId(listOf("p5"), 5))
    }

    @Test fun `separate memory only sees its own long-term items`() {
        val all = listOf(Memory(1, "shared", emptyList(), 1, 1), Memory(2, "own", emptyList(), 1, 1, profileId = "p1"),
            Memory(3, "note", emptyList(), 1, 1, chatId = 9))
        assertEquals(listOf(1L), MemoryText.globalFor(all, null).map { it.id })
        assertEquals(listOf(2L), MemoryText.globalFor(all, "p1").map { it.id })
        assertTrue("own" in MemoryText.promptBlock(all, true, 9, "p1")); assertFalse("shared" in MemoryText.promptBlock(all, true, 9, "p1"))
        assertNull(MemoryText.findDuplicate(all, "own", null, null))
        assertEquals(2L, MemoryText.findDuplicate(all, "own", null, "p1")?.id)
    }

    @Test fun `chat filter`() {
        val l = listOf(Profiles.DEFAULT, coding)
        val chats = listOf(1L to "default", 2L to "p1", 3L to "deleted")
        assertEquals(chats, Profiles.filterChats(chats, null, l) { it.second })
        assertEquals(listOf(2L), Profiles.filterChats(chats, "p1", l) { it.second }.map { it.first })
        assertEquals(listOf(1L, 3L), Profiles.filterChats(chats, "default", l) { it.second }.map { it.first })
    }
}
