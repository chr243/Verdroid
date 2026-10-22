package com.verdroid.app.data.profiles

import com.verdroid.app.data.memory.Memory
import com.verdroid.app.data.memory.MemoryText
import org.junit.Assert.*
import org.junit.Test

class ProfilesTest {
    private val coding = Profile("p1", "Coding", "💻", model = "kilo:x", tools = Profiles.ALL_TOOL_GROUPS.toSet() - "twitter", skills = setOf("a"),
        instructions = "Be terse.", memories = setOf(2L))

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
        assertEquals("p1", Profiles.memoryScope(coding)); assertNull(Profiles.memoryScope(coding.copy(memories = null)))
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

    @Test fun `selected memories, auto-add and all-memories visibility`() {
        val all = listOf(Memory(1, "shared", emptyList(), 1, 1), Memory(2, "own", emptyList(), 1, 1, profileId = "p1"),
            Memory(4, "other", emptyList(), 1, 1, profileId = "p9"))
        assertEquals(listOf(2L), all.filter { Profiles.memoryVisible(coding, it.id, it.profileId) }.map { it.id })
        assertEquals(listOf(1L), all.filter { Profiles.memoryVisible(Profiles.DEFAULT, it.id, it.profileId) }.map { it.id })
        val l = Profiles.addMemory(listOf(Profiles.DEFAULT, coding), "p1", 5)
        assertEquals(setOf(2L, 5L), l[1].memories); assertNull(l[0].memories)
        assertEquals(l, Profiles.addMemory(l, "default", 6))
    }

    @Test fun `test4 profiles migrate`() {
        val oldSep = Profile("s", "S", separateMemory = true, toolGroupsOff = setOf("git"))
        val oldShared = Profile("h", "H")
        val l = Profiles.migrateMemory(Profiles.normalize(listOf(oldSep, oldShared)), mapOf("s" to listOf(3L, 4L)))
        assertEquals(setOf(3L, 4L), l[1].memories); assertFalse(l[1].separateMemory); assertNull(l[2].memories)
        assertEquals(Profiles.ALL_TOOL_GROUPS.toSet() - "git", l[1].tools); assertTrue(l[1].toolGroupsOff.isEmpty())
        assertFalse(Profiles.toolAllowed("git", null, l[1], true)); assertTrue(Profiles.toolAllowed("git", null, l[2], true))
        assertTrue(Profiles.needsMemoryMigration(listOf(oldSep))); assertFalse(Profiles.needsMemoryMigration(l))
        // the stored JSON of test4 (no tools/memories keys) still decodes
        val raw = """[{"id":"s","name":"S","toolGroupsOff":["git"],"separateMemory":true}]"""
        assertEquals(Profiles.ALL_TOOL_GROUPS.toSet() - "git", Profiles.decode(raw)[1].tools)
        assertEquals(setOf(9L), Profiles.migrateMemory(Profiles.decode(raw), mapOf("s" to listOf(9L)))[1].memories)
    }

    @Test fun `tool presets dialog starts from the profile`() {
        val tp = com.verdroid.app.data.tools.ToolPreset
        val g = com.verdroid.app.data.tools.ToolPreset.entries.associateWith { com.verdroid.app.data.tools.ToolPreset.Companion.Global(2, 2) }
        val base = Profiles.presetBaseline(g, coding)
        assertFalse(tp.shownOn(com.verdroid.app.data.tools.ToolPreset.TWITTER, emptyMap(), base)); assertTrue(tp.shownOn(com.verdroid.app.data.tools.ToolPreset.WEB, emptyMap(), base))
        assertEquals(g, Profiles.presetBaseline(g, Profiles.DEFAULT))
        // turning Twitter on in the chat is a chat-only override; turning it back off clears it (= reset to profile)
        val on = tp.toggle(emptyMap(), com.verdroid.app.data.tools.ToolPreset.TWITTER, true, base)
        assertEquals(mapOf(com.verdroid.app.data.tools.ToolPreset.TWITTER to true), on)
        assertTrue(Profiles.toolAllowed("twitter", on[com.verdroid.app.data.tools.ToolPreset.TWITTER], coding, true))
        assertEquals(emptyMap<Any, Any>(), tp.toggle(on, com.verdroid.app.data.tools.ToolPreset.TWITTER, false, base))
    }

    @Test fun `chat filter`() {
        val l = listOf(Profiles.DEFAULT, coding)
        val chats = listOf(1L to "default", 2L to "p1", 3L to "deleted")
        assertEquals(chats, Profiles.filterChats(chats, null, l) { it.second })
        assertEquals(listOf(2L), Profiles.filterChats(chats, "p1", l) { it.second }.map { it.first })
        assertEquals(listOf(1L, 3L), Profiles.filterChats(chats, "default", l) { it.second }.map { it.first })
    }
}
