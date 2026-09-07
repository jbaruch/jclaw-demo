package jclaw

import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.asTools

/**
 * Tools sliced by what they can do to the world, not by which server they
 * came from. Each phase of the pipeline gets only the capability it needs -
 * and the slicing has narrative consequences: `deploy` cannot talk to anyone,
 * so it has to commit to a plan silently and let the critic do the talking.
 */
class Slices(registry: ToolRegistry, userTools: UserTools? = null) {

    /** Reaches Baruch. Interrupts him, but nothing leaves the building. */
    val user: List<ToolBase<*, *>> = userTools?.asTools() ?: emptyList()

    private val byName: Map<String, ToolBase<*, *>> = registry.tools.associateBy { it.name }

    private fun pick(vararg names: String): List<ToolBase<*, *>> = names.map {
        requireNotNull(byName[it]) { "tool '$it' not offered by any MCP server" }
    }

    /** No side effects. Safe everywhere. */
    val read: List<ToolBase<*, *>> = pick("getCalendar", "getOrganizerSensitivity")

    /** Changes Baruch's world, but nobody else sees it yet. */
    val write: List<ToolBase<*, *>> = pick("createCalendarEvent")

    /** Reaches another human being. Irreversible. */
    val comms: List<ToolBase<*, *>> = pick("sendDecline")
}
