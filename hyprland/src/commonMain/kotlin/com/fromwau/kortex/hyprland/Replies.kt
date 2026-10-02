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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal const val WORKSPACES = "j/workspaces"
internal const val MONITORS = "j/monitors"
internal const val CLIENTS = "j/clients"
internal const val ACTIVE_WINDOW = "j/activewindow"
internal const val SUBMAP = "j/submap"
internal const val DEVICES = "j/devices"

@Serializable
internal data class WorkspaceReply(
    val id: Int,
    val name: String,
    val monitor: String,
    val windows: Int,
)

@Serializable
internal data class MonitorReply(
    val id: Int,
    val name: String,
    val description: String,
    val focused: Boolean,
    val activeWorkspace: WorkspaceRef,
    val specialWorkspace: WorkspaceRef,
)

@Serializable
internal data class WorkspaceRef(val id: Int)

@Serializable
internal data class ClientReply(
    val address: String,
    val workspace: WorkspaceRef,
)

@Serializable
internal data class WindowReply(
    val address: String,
    @SerialName("class") val appId: String,
    val title: String,
    val workspace: WorkspaceRef,
)

@Serializable
internal data class DevicesReply(val keyboards: List<KeyboardReply>)

@Serializable
internal data class KeyboardReply(
    val name: String,
    /** Every layout the keyboard switches between, comma separated: `us,at`. */
    val layout: String,
    /** A number, or a bare `none` where Hyprland has no index, which a primitive takes as it is. */
    @SerialName("active_layout_index") val activeLayoutIndex: JsonPrimitive,
    @SerialName("active_keymap") val activeKeymap: String,
    val main: Boolean,
)

/**
 * Every monitor with the workspaces on it, ordered by id.
 *
 * @param urgent the workspaces holding a window that asked for attention.
 */
internal fun monitorsFrom(
    workspaces: List<WorkspaceReply>,
    monitors: List<MonitorReply>,
    urgent: Set<WorkspaceId>,
): List<Monitor> {
    val onMonitor = workspaces.groupBy(
        keySelector = { reply -> reply.monitor },
        valueTransform = { reply ->
            val id = WorkspaceId(reply.id)
            Workspace(id, reply.name, reply.windows, urgent = id in urgent)
        },
    )

    return monitors
        .sortedBy { it.id }
        .map { reply ->
            Monitor(
                id = reply.id,
                name = reply.name,
                description = reply.description,
                focused = reply.focused,
                workspaces = onMonitor[reply.name].orEmpty().sortedBy { it.id.value },
                active = WorkspaceId(reply.activeWorkspace.id),
                // Hyprland reports id 0 for no special workspace, which is no workspace's id.
                special = reply.specialWorkspace.id.takeIf { it != 0 }?.let(::WorkspaceId),
            )
        }
}

/** The workspaces holding one of [windows], from what `j/clients` says each window is on. */
internal fun workspacesHolding(
    windows: Set<WindowAddress>,
    clients: List<ClientReply>,
): Set<WorkspaceId> = clients
    .filter { WindowAddress(it.address) in windows }
    .map { WorkspaceId(it.workspace.id) }
    .toSet()

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

/** The active submap, or null for the default one, which Hyprland answers as `"default"`. */
internal fun submapFrom(answer: String): Result<String?, HyprlandError> =
    decode(SUBMAP, answer, String.serializer()).map { name -> name.takeUnless { it == DEFAULT_SUBMAP } }

/** The main keyboard's layout, or null where Hyprland calls no keyboard main. */
internal fun keyboardLayoutFrom(answer: String): Result<KeyboardLayout?, HyprlandError> =
    decode(DEVICES, answer, DevicesReply.serializer()).map { devices ->
        devices.keyboards.firstOrNull { it.main }?.let { keyboard ->
            KeyboardLayout(
                keyboard = keyboard.name,
                keymap = keyboard.activeKeymap,
                code = keyboard.activeLayoutIndex.intOrNull
                    ?.let { index -> keyboard.layout.split(',').getOrNull(index) }
                    ?.trim(),
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
    if (text.firstOrNull() !in JSON_OPENERS) return Err(HyprlandError.Refused(request, answer.trim()))
    return try {
        Ok(json.decodeFromString(strategy, text))
    } catch (failure: SerializationException) {
        Err(HyprlandError.Unparseable(request, failure.message ?: "unreadable"))
    }
}

private val json = Json { ignoreUnknownKeys = true }

// `j/submap` answers a bare JSON string, so a quote opens JSON as much as a brace does.
private val JSON_OPENERS = setOf('{', '[', '"')
private const val DEFAULT_SUBMAP = "default"
