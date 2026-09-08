package dev.evesharedmap.server.route

import dev.evesharedmap.server.service.ServiceException
import dev.evesharedmap.server.universe.SolarSystemAllowlist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RouteHandoffValidationTest {
    private val validation = RouteHandoffValidation(SolarSystemAllowlist.load())

    @Test
    fun `Normal and Capital intent plus resolved snapshots validate`() {
        assertEquals(normalDraft(), validation.validate(normalDraft()))
        assertEquals(capitalDraft(), validation.validate(capitalDraft()))
    }

    @Test
    fun `unknown systems path mismatch waypoint order and route-specific fields fail closed`() {
        val cases = listOf(
            normalDraft().copy(originSystemId = Int.MAX_VALUE),
            normalDraft().copy(resolvedEdges = emptyList()),
            normalDraft().copy(waypointSystemIds = listOf(30002537, 30000142)),
            normalDraft().copy(capitalRangeLy = 5.0),
            normalDraft().copy(resolvedEdges = normalDraft().resolvedEdges.map { it.copy(type = "CAPITAL") }),
            capitalDraft().copy(capitalRangeLy = 0.0),
            capitalDraft().copy(resolvedEdges = capitalDraft().resolvedEdges.map { it.copy(distanceLy = 6.1) }),
        )
        cases.forEach { draft ->
            val error = assertFailsWith<ServiceException> { validation.validate(draft) }
            assertEquals(422, error.status)
            assertEquals("INVALID_ARGUMENT", error.code)
        }
    }
}

internal fun normalDraft() = RouteHandoffDraft(
    type = RouteHandoffType.NORMAL,
    originSystemId = 30000142,
    waypointSystemIds = listOf(30002537),
    destinationSystemId = 30004759,
    useAnsiblex = true,
    capitalRangeLy = null,
    jumpProfileId = null,
    resolvedSystemIds = listOf(30000142, 30002537, 30004759),
    resolvedEdges = listOf(
        RouteHandoffResolvedEdge(30000142, 30002537, "ANSIBLEX", null),
        RouteHandoffResolvedEdge(30002537, 30004759, "STARGATE", null),
    ),
    mapMetadata = RouteHandoffMapMetadata("sde-3466501", "1.8.0", "pack-1"),
)

internal fun capitalDraft() = RouteHandoffDraft(
    type = RouteHandoffType.CAPITAL,
    originSystemId = 30000142,
    waypointSystemIds = listOf(30002537),
    destinationSystemId = 30004759,
    useAnsiblex = null,
    capitalRangeLy = 6.0,
    jumpProfileId = "capital-manual",
    resolvedSystemIds = listOf(30000142, 30002537, 30004759),
    resolvedEdges = listOf(
        RouteHandoffResolvedEdge(30000142, 30002537, "CAPITAL", 5.5),
        RouteHandoffResolvedEdge(30002537, 30004759, "CAPITAL", 5.9),
    ),
    mapMetadata = RouteHandoffMapMetadata("sde-3466501", "1.8.0", null),
)
