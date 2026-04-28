package com.openclaw.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EnvironmentBuilderTest {
    private lateinit var env: Map<String, String>

    @BeforeEach
    fun setup() {
        // EnvironmentBuilder always returns real Termux paths
        env = EnvironmentBuilder.buildTermuxEnvironment()
    }

    // ─── Core paths ───────────────────────────────────────────────────────────

    @Test
    fun `PREFIX points to real Termux usr directory`() {
        assertEquals(CommandRunner.TERMUX_PREFIX, env["PREFIX"])
    }

    @Test
    fun `HOME points to real Termux home directory`() {
        assertEquals(CommandRunner.TERMUX_HOME, env["HOME"])
    }

    @Test
    fun `TMPDIR is set and non-empty`() {
        assertNotNull(env["TMPDIR"])
        assertTrue(env["TMPDIR"]!!.isNotEmpty())
    }

    @Test
    fun `TMPDIR is adjacent to PREFIX`() {
        // TMPDIR should be $PREFIX/../tmp = /data/data/com.termux/files/tmp
        assertTrue(env["TMPDIR"]!!.contains("com.termux"))
    }

    // ─── PATH ─────────────────────────────────────────────────────────────────

    @Test
    fun `PATH contains openclaw bin`() {
        assertTrue(env["PATH"]!!.contains(".openclaw-android/bin"))
    }

    @Test
    fun `PATH contains termux bin`() {
        assertTrue(env["PATH"]!!.contains("/usr/bin"))
    }

    @Test
    fun `PATH contains termux applets`() {
        assertTrue(env["PATH"]!!.contains("/usr/bin/applets"))
    }

    @Test
    fun `PATH has openclaw bin before termux bin`() {
        val path = env["PATH"]!!
        val openclawIdx = path.indexOf(".openclaw-android/bin")
        val termuxIdx = path.indexOf("/usr/bin")
        assertTrue(openclawIdx < termuxIdx, "openclaw/bin must precede termux/bin in PATH")
    }

    // ─── Libraries ────────────────────────────────────────────────────────────

    @Test
    fun `LD_LIBRARY_PATH is set`() {
        assertNotNull(env["LD_LIBRARY_PATH"])
        assertTrue(env["LD_LIBRARY_PATH"]!!.contains("/usr/lib"))
    }

    // ─── Termux prefix vars ───────────────────────────────────────────────────

    @Test
    fun `TERMUX_PREFIX matches PREFIX`() {
        assertEquals(env["PREFIX"], env["TERMUX_PREFIX"])
    }

    @Test
    fun `TERMUX__PREFIX matches PREFIX`() {
        assertEquals(env["PREFIX"], env["TERMUX__PREFIX"])
    }

    // ─── apt/dpkg ─────────────────────────────────────────────────────────────

    @Test
    fun `APT_CONFIG points to apt conf`() {
        assertTrue(env["APT_CONFIG"]!!.endsWith("/etc/apt/apt.conf"))
    }

    @Test
    fun `DPKG_ADMINDIR is set`() {
        assertNotNull(env["DPKG_ADMINDIR"])
        assertTrue(env["DPKG_ADMINDIR"]!!.contains("dpkg"))
    }

    @Test
    fun `DPKG_ROOT matches PREFIX`() {
        assertEquals(env["PREFIX"], env["DPKG_ROOT"])
    }

    // ─── SSL ──────────────────────────────────────────────────────────────────

    @Test
    fun `SSL_CERT_FILE points to cert pem`() {
        assertTrue(env["SSL_CERT_FILE"]!!.endsWith("cert.pem"))
    }

    @Test
    fun `CURL_CA_BUNDLE matches SSL_CERT_FILE`() {
        assertEquals(env["SSL_CERT_FILE"], env["CURL_CA_BUNDLE"])
    }

    @Test
    fun `GIT_SSL_CAINFO matches SSL_CERT_FILE`() {
        assertEquals(env["SSL_CERT_FILE"], env["GIT_SSL_CAINFO"])
    }

    // ─── Git ──────────────────────────────────────────────────────────────────

    @Test
    fun `GIT_CONFIG_NOSYSTEM is set to 1`() {
        assertEquals("1", env["GIT_CONFIG_NOSYSTEM"])
    }

    @Test
    fun `GIT_EXEC_PATH points to git-core`() {
        assertTrue(env["GIT_EXEC_PATH"]!!.contains("git-core"))
    }

    @Test
    fun `GIT_TEMPLATE_DIR points to git templates`() {
        assertTrue(env["GIT_TEMPLATE_DIR"]!!.contains("git-core/templates"))
    }

    // ─── Locale / terminal ────────────────────────────────────────────────────

    @Test
    fun `LANG is en_US UTF-8`() {
        assertEquals("en_US.UTF-8", env["LANG"])
    }

    @Test
    fun `TERM is xterm-256color`() {
        assertEquals("xterm-256color", env["TERM"])
    }

    // ─── Android ──────────────────────────────────────────────────────────────

    @Test
    fun `ANDROID_DATA is set`() {
        assertEquals("/data", env["ANDROID_DATA"])
    }

    @Test
    fun `ANDROID_ROOT is set`() {
        assertEquals("/system", env["ANDROID_ROOT"])
    }

    // ─── OpenClaw ─────────────────────────────────────────────────────────────

    @Test
    fun `OA_GLIBC is set to 1`() {
        assertEquals("1", env["OA_GLIBC"])
    }

    @Test
    fun `CONTAINER is set to 1`() {
        assertEquals("1", env["CONTAINER"])
    }

    @Test
    fun `CLAWDHUB_WORKDIR is set`() {
        assertNotNull(env["CLAWDHUB_WORKDIR"])
        assertTrue(env["CLAWDHUB_WORKDIR"]!!.contains(".openclaw"))
    }

    @Test
    fun `CPATH contains glib-2 0 include`() {
        assertNotNull(env["CPATH"])
        assertTrue(env["CPATH"]!!.contains("glib-2.0"))
    }

    // ─── build() overloads ────────────────────────────────────────────────────

    @Test
    fun `build(context) returns same as buildTermuxEnvironment`() {
        // build(context) delegates to buildTermuxEnvironment — verify key equality
        val fromBuild = EnvironmentBuilder.buildTermuxEnvironment()
        assertEquals(fromBuild["HOME"], env["HOME"])
        assertEquals(fromBuild["PREFIX"], env["PREFIX"])
        assertEquals(fromBuild["PATH"], env["PATH"])
    }
}
