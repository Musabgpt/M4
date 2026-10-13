package com.musabgpt.m4.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserSecurityTest {
    @Test fun permitsEncryptedAndExplicitLoopbackPages() {
        assertTrue(BrowserSecurity.allowed("https://example.com/path"))
        assertTrue(BrowserSecurity.allowed("http://127.0.0.1:8000/"))
        assertTrue(BrowserSecurity.allowed("http://localhost:3000/"))
    }
    @Test fun rejectsFilesCredentialsScriptsAndRemoteCleartext() {
        for (url in listOf("file:///etc/passwd", "content://app/private", "javascript:alert(1)",
            "https://name:secret@example.com/", "http://example.com/", "not a url")) assertFalse(url, BrowserSecurity.allowed(url))
    }
}
