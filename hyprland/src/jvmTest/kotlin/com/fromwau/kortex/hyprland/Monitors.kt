package com.fromwau.kortex.hyprland

/** Every workspace on every monitor, by id, for a test that asks about one regardless of where it is. */
internal val List<Monitor>.allWorkspaces: List<Workspace> get() = flatMap { it.workspaces }.sortedBy { it.id.value }

/** The monitor with focus. */
internal val List<Monitor>.focused: Monitor get() = single { it.focused }

/** The workspace the monitor connected at [name] shows. */
internal fun List<Monitor>.activeOn(name: String): WorkspaceId? = firstOrNull { it.name == name }?.active
