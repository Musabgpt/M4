package com.musabgpt.m4

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the Android command fast path: no LLM or network involved. */
class NativeQuickActionTest {
    @Test fun opensWhatsAppDirectly() {
        assertEquals("native:افتح واتساب", M4NativeTools.quickAction("افتح واتساب"))
    }

    @Test fun stripsTerminalSuffixInsteadOfTreatingItAsAnAppName() {
        assertEquals("native:افتح واتساب",
            M4NativeTools.quickAction("افتح واتساب باستخدام الطرفية"))
    }

    @Test fun routesGitHubToOfficialLogin() {
        assertEquals("github_connect", M4NativeTools.quickAction("اربط GitHub"))
    }

    @Test fun ordinaryChatIsNotMisclassifiedAsPhoneAction() {
        assertEquals(null, M4NativeTools.quickAction("اكتب لي كود آلة حاسبة"))
        assertEquals(null, M4NativeTools.quickAction("اشرح لي Kotlin"))
    }

    @Test fun fallbackCatalogHasFourFixedFamilies() {
        assertEquals(4, ProviderCatalog.all.size)
        assertTrue(ProviderCatalog.routingOrder(ProviderCatalog.all[0]).isNotEmpty())
    }
}
