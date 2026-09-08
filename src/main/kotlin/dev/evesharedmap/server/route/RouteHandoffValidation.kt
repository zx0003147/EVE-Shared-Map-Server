package dev.evesharedmap.server.route

import dev.evesharedmap.server.service.ServiceErrors
import dev.evesharedmap.server.universe.SolarSystemAllowlist

class RouteHandoffValidation(private val allowlist: SolarSystemAllowlist) {
    fun validate(draft: RouteHandoffDraft): RouteHandoffDraft {
        val allIds = buildList {
            add(draft.originSystemId)
            addAll(draft.waypointSystemIds)
            add(draft.destinationSystemId)
            addAll(draft.resolvedSystemIds)
            draft.resolvedEdges.forEach { add(it.fromSystemId); add(it.toSystemId) }
        }
        if (allIds.any { it !in allowlist }) invalid("systemIds", "UNKNOWN_SYSTEM")
        if (draft.waypointSystemIds.size > MAX_WAYPOINTS) invalid("waypointSystemIds", "TOO_MANY")
        if (draft.resolvedSystemIds.isEmpty() || draft.resolvedSystemIds.size > MAX_RESOLVED_SYSTEMS) {
            invalid("resolvedSystemIds", "INVALID_SIZE")
        }
        if (draft.resolvedSystemIds.first() != draft.originSystemId ||
            draft.resolvedSystemIds.last() != draft.destinationSystemId
        ) invalid("resolvedSystemIds", "ENDPOINT_MISMATCH")
        if (draft.resolvedEdges.size != draft.resolvedSystemIds.size - 1) {
            invalid("resolvedEdges", "PATH_LENGTH_MISMATCH")
        }
        draft.resolvedEdges.forEachIndexed { index, edge ->
            if (edge.fromSystemId != draft.resolvedSystemIds[index] ||
                edge.toSystemId != draft.resolvedSystemIds[index + 1]
            ) invalid("resolvedEdges", "PATH_ORDER_MISMATCH")
        }
        var waypointSearchFrom = 0
        draft.waypointSystemIds.forEach { waypoint ->
            val found = draft.resolvedSystemIds.indexOfFirstFrom(waypointSearchFrom) { it == waypoint }
            if (found < 0) invalid("waypointSystemIds", "NOT_IN_RESOLVED_PATH")
            waypointSearchFrom = found + 1
        }
        if (draft.mapMetadata.universeBuild.isBlank() || draft.mapMetadata.universeBuild.length > 128) {
            invalid("mapMetadata.universeBuild", "INVALID_LENGTH")
        }
        if (draft.mapMetadata.plannerVersion.isBlank() || draft.mapMetadata.plannerVersion.length > 64) {
            invalid("mapMetadata.plannerVersion", "INVALID_LENGTH")
        }
        draft.mapMetadata.webPackVersion?.let {
            if (it.isBlank() || it.length > 64) invalid("mapMetadata.webPackVersion", "INVALID_LENGTH")
        }
        when (draft.type) {
            RouteHandoffType.NORMAL -> {
                if (draft.useAnsiblex == null || draft.capitalRangeLy != null || draft.jumpProfileId != null) {
                    invalid("type", "NORMAL_FIELDS_INVALID")
                }
                if (draft.resolvedEdges.any { it.type !in NORMAL_EDGE_TYPES || it.distanceLy != null }) {
                    invalid("resolvedEdges", "NORMAL_EDGE_INVALID")
                }
            }
            RouteHandoffType.CAPITAL -> {
                val range = draft.capitalRangeLy
                if (draft.useAnsiblex != null || range == null || !range.isFinite() || range <= 0.0 || range > 50.0) {
                    invalid("capitalRangeLy", "CAPITAL_FIELDS_INVALID")
                }
                if (draft.jumpProfileId.isNullOrBlank() || draft.jumpProfileId.length > 80) {
                    invalid("jumpProfileId", "INVALID_LENGTH")
                }
                if (draft.resolvedEdges.any {
                        it.type != "CAPITAL" || it.distanceLy == null || !it.distanceLy.isFinite() ||
                            it.distanceLy < 0.0 || it.distanceLy > range + 1e-9
                    }
                ) invalid("resolvedEdges", "CAPITAL_EDGE_INVALID")
            }
        }
        return draft
    }

    private fun invalid(field: String, reason: String): Nothing = throw ServiceErrors.invalid(field, reason)

    private fun <T> List<T>.indexOfFirstFrom(fromIndex: Int, predicate: (T) -> Boolean): Int {
        for (index in fromIndex until size) if (predicate(this[index])) return index
        return -1
    }

    private companion object {
        const val MAX_WAYPOINTS = 50
        const val MAX_RESOLVED_SYSTEMS = 500
        val NORMAL_EDGE_TYPES = setOf("STARGATE", "ANSIBLEX", "WORMHOLE")
    }
}
