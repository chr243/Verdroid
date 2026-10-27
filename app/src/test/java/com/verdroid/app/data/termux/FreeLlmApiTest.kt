package com.verdroid.app.data.termux

import com.verdroid.app.data.network.ProviderPresets
import com.verdroid.app.data.network.ProviderProbe
import com.verdroid.app.data.network.ProviderUrls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeLlmApiTest {
    @Test fun presetIsLocalKeyedProvider() {
        val p = ProviderPresets.find(FreeLlmApi.PROVIDER_ID)!!
        assertEquals("http://127.0.0.1:3001/v1/", p.baseUrl)
        assertTrue(p.needsKey)
        assertTrue(ProviderUrls.cleartextAllowed(p.baseUrl))
        assertTrue(ProviderProbe.isLocal(p.baseUrl))
        assertEquals("Custom", ProviderPresets.ALL.last().label) // byId fallback stays Custom
    }

    @Test fun installScriptIsIdempotentAndLogs() {
        val s = FreeLlmApi.installScript()
        assertTrue("pkg install -y nodejs-lts git" in s)
        assertTrue("-lt 22013" in s && "too old" in s)
        assertTrue("git -C \"\$D\" pull --ff-only" in s && "git clone --depth 1" in s)
        assertTrue("tee -a \"\$L/install.log\"" in s)
        assertFalse("rm " in s)
        assertTrue("--include=dev" in s && "NODE_ENV=development" in s && "unset NODE_ENV" in s)
        assertFalse("--omit=optional" in s)
        assertTrue("read -r _" in s && "PIPESTATUS" in s)
        assertTrue("concurrently tsx vite" in s && "retrying npm install once" in s)
    }

    @Test fun startStopScripts() {
        val start = FreeLlmApi.startScript()
        assertTrue("termux-wake-lock" in start && "nohup npm run dev" in start && FreeLlmApi.ALREADY_RUNNING in start)
        assertFalse("0.0.0.0" in start)
        assertTrue("node_modules/.bin/concurrently" in start)
        val stop = FreeLlmApi.stopScript()
        assertTrue("termux-wake-unlock" in stop && FreeLlmApi.STOPPED in stop)
        assertFalse("rm " in stop)
    }

    @Test fun startMessages() {
        assertTrue("Install / Update again" in FreeLlmApi.startMessage(FreeLlmApi.NOT_INSTALLED))
        assertEquals("Already running.", FreeLlmApi.startMessage(FreeLlmApi.ALREADY_RUNNING))
        assertTrue("Starting" in FreeLlmApi.startMessage("x\n" + FreeLlmApi.STARTED))
        assertTrue("Termux" in FreeLlmApi.startMessage(null))
    }

    @Test fun scriptsParseInBash() {
        val bash = listOf("/bin/bash", "/usr/bin/bash").map { java.io.File(it) }.firstOrNull { it.canExecute() } ?: return
        for (s in listOf(FreeLlmApi.installScript(), FreeLlmApi.startScript(), FreeLlmApi.stopScript(), FreeLlmApi.logScript())) {
            val f = java.io.File.createTempFile("fla", ".sh").apply { writeText(s); deleteOnExit() }
            val p = ProcessBuilder(bash.path, "-n", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(out, 0, p.waitFor())
        }
    }
}
