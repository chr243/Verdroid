package com.verdroid.app.domain

import com.verdroid.app.domain.model.DefaultModels
import org.junit.Assert.*
import org.junit.Test

class DefaultModelsTest {
    @Test fun `new default order`() {
        assertEquals(listOf(
            "nvidia/nemotron-3-ultra-550b-a55b:free",
            "openrouter/free",
            "kilo:kilo-auto/free",
        ), DefaultModels.list)
        assertNotEquals("openrouter/free", DefaultModels.list.last())
    }

    @Test fun `only an uncustomised legacy list is migrated`() {
        assertTrue(DefaultModels.isUncustomisedLegacy(DefaultModels.legacyV1.joinToString("\n")))
        assertTrue(DefaultModels.isUncustomisedLegacy(DefaultModels.legacyV1.joinToString("\n") { " $it " } + "\n"))
        assertFalse(DefaultModels.isUncustomisedLegacy(null))
        assertFalse(DefaultModels.isUncustomisedLegacy(DefaultModels.legacyV1.reversed().joinToString("\n")))
        assertFalse(DefaultModels.isUncustomisedLegacy((DefaultModels.legacyV1 + "x/y:free").joinToString("\n")))
        assertFalse(DefaultModels.isUncustomisedLegacy(DefaultModels.list.joinToString("\n")))
        assertTrue(DefaultModels.isUncustomisedLegacy(DefaultModels.previousV3.joinToString("\n")))
        assertFalse(DefaultModels.isUncustomisedLegacy((DefaultModels.previousV3 - "openrouter/free").joinToString("\n")))
    }

    @Test fun `without an OpenRouter key the default chain skips OpenRouter and uses kilo`() {
        val m = DefaultModels.list
        val or = m.filter(com.verdroid.app.data.network.ModelIds::isOpenRouter)
        assertEquals(listOf("nvidia/nemotron-3-ultra-550b-a55b:free", "openrouter/free"), or)
        assertFalse(com.verdroid.app.data.network.ModelIds.openRouterUsable(or, keyCount = 0))
        assertTrue(com.verdroid.app.data.network.ModelIds.openRouterUsable(or, keyCount = 1))
        assertEquals(listOf("kilo:kilo-auto/free"), m.filter(com.verdroid.app.data.network.ModelIds::isKilo))
    }
}
