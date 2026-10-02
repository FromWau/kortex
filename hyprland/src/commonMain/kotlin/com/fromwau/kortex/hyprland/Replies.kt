package com.fromwau.kortex.hyprland

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal const val WORKSPACES = "j/workspaces"
internal const val MONITORS = "j/monitors"
internal const val ACTIVE_WINDOW = "j/activewindow"

@Serializable
internal data class WorkspaceReply(
    val id: Int,
    val name: String,
    val monitor: String,
    val windows: Int,
)

@Serializable
internal data class MonitorReply(
    val name: String,
    val focused: Boolean,
    val activeWorkspace: WorkspaceRef,
)

@Serializable
internal data class WorkspaceRef(val id: Int)

@Serializable
internal data class WindowReply(
    val address: String,
    @SerialName("class") val appId: String,
    val title: String,
    val workspace: WorkspaceRef,
)

internal fun workspacesFrom(
    workspaces: List<WorkspaceReply>,
    monitors: List<MonitorReply>,
): Workspaces = Workspaces(
    all = workspaces
        .map { Workspace(WorkspaceId(it.id), it.name, it.monitor, it.windows) }
        .sortedBy { it.id.value },
    active = monitors.associate { it.name to WorkspaceId(it.activeWorkspace.id) },
    focusedMonitor = monitors.firstOrNull { it.focused }?.name,
)

/** The focused window, or null where Hyprland answers `{}` because nothing has focus. */
internal fun activeWindowFrom(answer: String): Result<ActiveWindow?, HyprlandError> =
    decode(ACTIVE_WINDOW, answer, JsonObject.serializer()).flatMap { reply ->
        if (reply.isEmpty()) return@flatMap Ok(null)
        decode(ACTIVE_WINDOW, answer, WindowReply.serializer()).map { window ->
            ActiveWindow(
                address = WindowAddress(window.address),
                appId = window.appId,
                title = window.title,
                workspace = WorkspaceId(window.workspace.id),
            )
        }
    }

/**
 * [answer] to [request] read as [strategy].
 *
 * An answer that does not open as JSON is Hyprland refusing the request in words, `unknown request`
 * being the one it gives a command it does not know, so it is reported as that rather than as bad JSON.
 */
internal fun <T> decode(
    request: String,
    answer: String,
    strategy: DeserializationStrategy<T>,
): Result<T, HyprlandError> {
    val text = answer.trimStart()
    if (!text.startsWith('{') && !text.startsWith('[')) return Err(HyprlandError.Refused(request, answer.trim()))
    return try {
        Ok(json.decodeFromString(strategy, text))
    } catch (failure: SerializationException) {
        Err(HyprlandError.Unparseable(request, failure.message ?: "unreadable"))
    }
}

private val json = Json { ignoreUnknownKeys = true }
