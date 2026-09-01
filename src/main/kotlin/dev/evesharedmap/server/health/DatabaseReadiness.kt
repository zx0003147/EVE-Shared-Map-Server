package dev.evesharedmap.server.health

import dev.evesharedmap.server.database.prepareHealthStatement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

fun interface ReadinessProbe {
    suspend fun databaseReady(): Boolean
}

class DatabaseReadiness(
    private val dataSource: DataSource,
) : ReadinessProbe {
    override suspend fun databaseReady(): Boolean = withContext(Dispatchers.IO) {
        try {
            dataSource.connection.use { connection ->
                connection.prepareHealthStatement().use { statement ->
                    statement.executeQuery().use { result ->
                        result.next() && result.getInt(1) == 1
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
