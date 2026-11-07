package dev.xshirae.neoplayersync

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConfigFileTest {
    private val dir = createTempDirectory()

    @Test
    fun `a missing file is a first start, not an error`() {
        assertNull(DatabaseConfig.tomlError(dir.resolve("neoplayersync.toml")))
    }

    @Test
    fun `a valid file passes`() {
        val file = dir.resolve("neoplayersync.toml").apply { writeText("enabled = true\nssl_root_cert = \"C:/certs/ca.crt\"\n") }
        assertNull(DatabaseConfig.tomlError(file))
    }

    @Test
    fun `a Windows path with backslashes is reported`() {
        val file = dir.resolve("neoplayersync.toml").apply { writeText("enabled = true\nssl_root_cert = \"C:\\certs\\ca.crt\"\n") }
        assertNotNull(DatabaseConfig.tomlError(file))
    }
}
