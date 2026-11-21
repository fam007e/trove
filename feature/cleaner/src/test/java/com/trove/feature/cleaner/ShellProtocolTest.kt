package com.trove.feature.cleaner

import com.trove.feature.cleaner.core.PackageNameValidator
import com.trove.feature.cleaner.core.ShellProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProtocolTest {

    @Test
    fun `frame wraps command with matching sentinels`() {
        val lines = ShellProtocol.frame(7, "id -u")
        assertEquals(listOf("echo TROVE_S_7_", "id -u", "echo TROVE_E_7_\$?"), lines)
    }

    @Test
    fun `classify recognizes start and end markers`() {
        assertTrue(ShellProtocol.classify("TROVE_S_12_") is ShellProtocol.Line.Start)
        val end = ShellProtocol.classify("TROVE_E_12_0")
        assertTrue(end is ShellProtocol.Line.End)
        assertEquals(12, (end as ShellProtocol.Line.End).id)
        assertEquals(0, end.exitCode)
    }

    @Test
    fun `classify treats command output as payload`() {
        assertTrue(ShellProtocol.classify("com.example.app") is ShellProtocol.Line.Payload)
        assertTrue(ShellProtocol.classify("") is ShellProtocol.Line.Payload)
        // Marker prefixes with a non-numeric id are payload, not sentinels.
        assertTrue(ShellProtocol.classify("TROVE_S_x") is ShellProtocol.Line.Payload)
    }

    @Test
    fun `du lines parse with tab or space separator`() {
        assertEquals(12L to "/data/data/com.foo/cache", ShellProtocol.parseDuLine("12\t/data/data/com.foo/cache"))
        assertEquals(3L to "/data/anr/trace", ShellProtocol.parseDuLine("3 /data/anr/trace"))
        assertNull(ShellProtocol.parseDuLine("notdu"))
        assertNull(ShellProtocol.parseDuLine(""))
    }

    @Test
    fun `du listing converts kilobytes to bytes and keys by path`() {
        val map = ShellProtocol.parseDuListing("2\t/data/data/a/cache\n10 /data/data/b/cache")
        assertEquals(2048L, map["/data/data/a/cache"])
        assertEquals(10240L, map["/data/data/b/cache"])
    }

    @Test
    fun `id output parses to uid`() {
        assertEquals(0, ShellProtocol.parseIdOutput("0\n"))
        assertEquals(2000, ShellProtocol.parseIdOutput("noise\n2000"))
        assertNull(ShellProtocol.parseIdOutput(""))
    }
}

class PackageNameValidatorTest {

    @Test
    fun `valid package names pass`() {
        assertTrue(PackageNameValidator.isValid("com.example.app"))
        assertTrue(PackageNameValidator.isValid("android"))
        assertTrue(PackageNameValidator.isValid("org.a_1.b-2".replace('-', '.')))
    }

    @Test
    fun `traversal and injection attempts are rejected`() {
        assertFalse(PackageNameValidator.isValid(".."))
        assertFalse(PackageNameValidator.isValid("../.."))
        assertFalse(PackageNameValidator.isValid("com.foo;rm -rf /"))
        assertFalse(PackageNameValidator.isValid("com.foo bar"))
        assertFalse(PackageNameValidator.isValid("com.foo\nbar"))
        assertFalse(PackageNameValidator.isValid("com.\"foo\""))
        assertFalse(PackageNameValidator.isValid("com.\$foo"))
        assertFalse(PackageNameValidator.isValid(""))
        assertFalse(PackageNameValidator.isValid("9starts.digit"))
        assertFalse(PackageNameValidator.isValid(".hidden"))
    }

    @Test
    fun `overlong names are rejected`() {
        val long = "a".repeat(201)
        assertFalse(PackageNameValidator.isValid(long))
    }

    @Test
    fun `shell quoting escapes dangerous characters`() {
        assertEquals("\"com.foo\"", PackageNameValidator.shellQuote("com.foo"))
        assertTrue(PackageNameValidator.shellQuote("a\$b").contains("\\\$"))
        assertTrue(PackageNameValidator.shellQuote("a\"b").contains("\\\""))
    }
}
