package dev.xshirae.neoplayersync

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigFileTest {
    private val dir = createTempDirectory()

    @Test
    fun `a missing file is a first start, not an error`() {
        assertNull(DatabaseConfig.fileProblem(dir.resolve("neoplayersync.toml")))
    }

    @Test
    fun `a valid file passes`() {
        val file = dir.resolve("neoplayersync.toml").apply { writeText("enabled = true\nssl_root_cert = \"C:/certs/ca.crt\"\n") }
        assertNull(DatabaseConfig.fileProblem(file))
    }

    @Test
    fun `a Windows path with backslashes is reported`() {
        val file = dir.resolve("neoplayersync.toml").apply { writeText("enabled = true\nssl_root_cert = \"C:\\certs\\ca.crt\"\n") }
        assertNotNull(DatabaseConfig.fileProblem(file))
    }

    @Test
    fun `defaults written with a backup mean NeoForge replaced a broken file`() {
        val file = configWithBackup(enabled = false, backupAgeMillis = 1_000)
        assertNotNull(DatabaseConfig.fileProblem(file))
    }

    @Test
    fun `an old backup does not block a server the operator turned off`() {
        val file = configWithBackup(enabled = false, backupAgeMillis = 3_600_000)
        assertNull(DatabaseConfig.fileProblem(file))
    }

    @Test
    fun `a file with sync on starts next to a fresh backup`() {
        val file = configWithBackup(enabled = true, backupAgeMillis = 0)
        assertNull(DatabaseConfig.fileProblem(file))
    }

    @Test
    fun `a backup NeoForge made while adding a missing key does not block a server turned off`() {
        val file = configWithBackup(enabled = false, backupAgeMillis = 0, backupText = "enabled = false\n")
        assertNull(DatabaseConfig.fileProblem(file))
    }

    private fun configWithBackup(enabled: Boolean, backupAgeMillis: Long, backupText: String = "﻿enabled = true\n"): Path {
        val file = dir.resolve("neoplayersync.toml").apply { writeText("enabled = $enabled\n") }
        val backup = dir.resolve("neoplayersync-1.toml.bak").apply { writeText(backupText) }
        val written = Files.getLastModifiedTime(file).toMillis()
        Files.setLastModifiedTime(backup, FileTime.fromMillis(written - backupAgeMillis))
        return file
    }
}
