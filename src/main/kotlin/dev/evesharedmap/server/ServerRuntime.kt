package dev.evesharedmap.server

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

class ServerRuntime(
    private val startServer: (wait: Boolean) -> Unit,
    private val stopServer: () -> Unit,
    private val databaseResource: AutoCloseable,
) : AutoCloseable {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val logger = LoggerFactory.getLogger(ServerRuntime::class.java)

    fun start(wait: Boolean) {
        check(!closed.get()) { "Server runtime is already closed." }
        check(started.compareAndSet(false, true)) { "Server runtime is already started." }
        startServer(wait)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        logger.info("event=shutdown stage=starting")
        try {
            if (started.get()) stopServer()
        } finally {
            databaseResource.close()
            logger.info("event=shutdown stage=complete")
        }
    }
}
