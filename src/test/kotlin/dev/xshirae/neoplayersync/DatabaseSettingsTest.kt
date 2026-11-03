package dev.xshirae.neoplayersync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseSettingsTest {
    private fun settings(
        host: String = "db.example.com",
        port: Int = 5432,
        database: String = "minecraft",
        user: String = "minecraft",
        password: String = "secret-value",
        serverName: String = "",
    ) = DatabaseSettings.validated(host, port, database, user, password, requireSsl = true, serverName = serverName)

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
    fun `the password never appears in text or errors`() {
        assertFalse("secret-value" in settings().toString())
        val message = assertFailsWith<IllegalArgumentException> { settings(host = "") }.message.orEmpty()
        assertFalse("secret-value" in message)
    }
}
