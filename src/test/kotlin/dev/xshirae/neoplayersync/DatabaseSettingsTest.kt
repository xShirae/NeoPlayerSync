package dev.xshirae.neoplayersync

import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DatabaseSettingsTest {
    private fun settings(
        host: String = "db.example.com",
        port: Int = 5432,
        database: String = "minecraft",
        user: String = "minecraft",
        password: String = "secret-value",
        requireSsl: Boolean = true,
        serverName: String = "",
        sslRootCert: String = "",
    ) = DatabaseSettings.validated(
        host = host,
        port = port,
        database = database,
        user = user,
        password = password,
        requireSsl = requireSsl,
        serverName = serverName,
        sslRootCert = sslRootCert,
    )

    @Test
    fun `valid settings are accepted`() {
        settings()
        settings(host = "[::1]")
    }

    @Test
    fun `blank required values are rejected and named`() {
        val message = assertFailsWith<IllegalArgumentException> {
            settings(host = "", database = " ", user = "", password = "")
        }.message.orEmpty()
        listOf("host", "database", "user", "password").forEach { assertTrue(it in message, "missing $it in: $message") }
    }

    @Test
    fun `values that would change the connection URL are rejected`() {
        assertFailsWith<IllegalArgumentException> { settings(host = "db.example.com/other") }
        assertFailsWith<IllegalArgumentException> { settings(database = "minecraft?sslmode=disable") }
    }

    @Test
    fun `an out of range port is rejected`() {
        assertFailsWith<IllegalArgumentException> { settings(port = 0) }
        assertFailsWith<IllegalArgumentException> { settings(port = 65_536) }
    }

    @Test
    fun `the server name is trimmed`() {
        assertEquals("na1", settings(serverName = "  na1 ").serverName)
    }

    @Test
    fun `without a root certificate the connection is encrypted but unchecked`() {
        val properties = settings().connectionProperties()
        assertEquals("require", properties.getProperty("sslmode"))
        assertNull(properties.getProperty("sslrootcert"))
        assertEquals("prefer", settings(requireSsl = false).connectionProperties().getProperty("sslmode"))
    }

    @Test
    fun `a root certificate makes the driver check the server's certificate and host name`() {
        val cert = createTempFile("neoplayersync-ca", ".crt")
        try {
            val properties = settings(sslRootCert = "  $cert ").connectionProperties()
            assertEquals("verify-full", properties.getProperty("sslmode"))
            assertEquals(cert.toString(), properties.getProperty("sslrootcert"))
        } finally {
            cert.deleteIfExists()
        }
    }

    @Test
    fun `a root certificate that is missing or has encryption off is rejected`() {
        val missing = assertFailsWith<IllegalArgumentException> { settings(sslRootCert = "no/such/ca.crt") }.message.orEmpty()
        assertTrue("ssl_root_cert" in missing, missing)

        val cert = createTempFile("neoplayersync-ca", ".crt")
        try {
            val plain = assertFailsWith<IllegalArgumentException> {
                settings(requireSsl = false, sslRootCert = cert.toString())
            }.message.orEmpty()
            assertTrue("require_ssl" in plain, plain)
        } finally {
            cert.deleteIfExists()
        }
    }

    /** Runs on each loader the build targets, so a renamed config type fails here before it can leak the password. */
    @Test
    fun `the config uses the loader's type that stays off clients`() {
        val expected = if ("LOCAL" in net.neoforged.fml.config.ModConfig.Type.values().map { it.name }) "LOCAL" else "COMMON"
        assertEquals(expected, NeoPlayerSync.localConfigType().name)
    }

    @Test
    fun `the password never appears in text or errors`() {
        assertFalse("secret-value" in settings().toString())
        val message = assertFailsWith<IllegalArgumentException> { settings(host = "") }.message.orEmpty()
        assertFalse("secret-value" in message)
    }
}
