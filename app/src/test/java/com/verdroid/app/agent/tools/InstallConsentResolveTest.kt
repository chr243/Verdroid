package com.verdroid.app.agent.tools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InstallConsentResolveTest {
    private val pkgs = listOf(PackageSpec("pip", "pypdf"))

    @Test fun dialogAnswersDecide() = runTest {
        var seen: List<PackageSpec>? = null
        assertEquals(InstallConsent.Decision.Allow, InstallConsent.resolve(buildJsonObject {}, "g", pkgs, "pdf_info", "why") { _, p, _, _ -> seen = p; true })
        assertEquals(pkgs, seen)
        assertEquals(InstallConsent.Decision.Deny, InstallConsent.resolve(buildJsonObject {}, "g", pkgs, "t", "r") { _, _, _, _ -> false })
    }

    @Test fun noAnswerOrNoUiFallsBackToAskInChat() = runTest {
        assertEquals(InstallConsent.Decision.Ask, InstallConsent.resolve(buildJsonObject {}, "g", pkgs, "t", "r") { _, _, _, _ -> null })
        assertEquals(InstallConsent.Decision.Ask, InstallConsent.resolve(buildJsonObject {}, "g", pkgs, "t", "r", confirmer = null))
        assertEquals(InstallConsent.Decision.Ask, InstallConsent.resolve(buildJsonObject {}, "g", pkgs, "t", "r") { _, _, _, _ -> throw IllegalStateException() })
    }

    @Test fun legacyConfirmPathStillWorksWithoutAsking() = runTest {
        var asked = false
        val args = buildJsonObject { put(InstallConsent.PARAM, true); put(InstallConsent.ID_PARAM, InstallConsent.installId("g", pkgs)) }
        assertEquals(InstallConsent.Decision.Allow, InstallConsent.resolve(args, "g", pkgs, "t", "r") { _, _, _, _ -> asked = true; false })
        assertFalse(asked)
        assertEquals(InstallConsent.Decision.Allow, InstallConsent.resolve(buildJsonObject {}, "g", emptyList(), "t", "r") { _, _, _, _ -> asked = true; false })
        assertFalse(asked)
    }
}
