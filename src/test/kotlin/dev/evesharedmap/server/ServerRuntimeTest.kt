package dev.evesharedmap.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerRuntimeTest {
    @Test
    fun `shutdown stops server and closes database exactly once`() {
        var starts = 0
        var stops = 0
        var closes = 0
        val runtime = ServerRuntime(
            startServer = { starts += 1 },
            stopServer = { stops += 1 },
            databaseResource = AutoCloseable { closes += 1 },
        )

        runtime.start(wait = false)
        runtime.close()
        runtime.close()

        assertEquals(1, starts)
        assertEquals(1, stops)
        assertEquals(1, closes)
    }

    @Test
    fun `database closes even if server stop fails`() {
        var closes = 0
        val runtime = ServerRuntime(
            startServer = {},
            stopServer = { error("stop failed") },
            databaseResource = AutoCloseable { closes += 1 },
        )
        runtime.start(wait = false)

        assertFailsWith<IllegalStateException> { runtime.close() }
        assertEquals(1, closes)
    }

    @Test
    fun `closed runtime cannot start`() {
        val runtime = ServerRuntime({}, {}, AutoCloseable {})
        runtime.close()

        assertFailsWith<IllegalStateException> { runtime.start(wait = false) }
    }
}
