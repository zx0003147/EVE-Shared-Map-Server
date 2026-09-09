package dev.evesharedmap.server

import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import kotlin.test.assertEquals

class EveMapCliSymlinkIntegrationTest {
    @Test
    fun `Ubuntu CLI direct installed and symlink invocations are equivalent`() {
        val container = UbuntuContainer()
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("ops")), "/workspace/ops")
            .withCommand("sleep", "infinity")
        container.start()
        try {
            val result = container.execInContainer(
                "bash",
                "-lc",
                "chmod +x /workspace/ops/eve-map /workspace/ops/tests/eve-map-cli-symlink-tests.sh && " +
                    "/workspace/ops/tests/eve-map-cli-symlink-tests.sh",
            )
            assertEquals(0, result.exitCode, result.stdout + result.stderr)
        } finally {
            container.stop()
        }
    }
}

private class UbuntuContainer : GenericContainer<UbuntuContainer>(DockerImageName.parse("ubuntu:24.04"))
