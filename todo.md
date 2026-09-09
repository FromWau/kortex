# Feature parity with OShane-McKenzie/wayland

Tracking against that project's README. It is **GPL-3.0**; kortex is **Apache-2.0**. Those combine in one
direction only — Apache-2.0 code may be taken into a GPLv3 work, never the reverse — so nothing from that
repo can land here. Read it for protocol structure; build from the wlroots XML and the Wayland spec.

## Already there

- [x] `zwlr_layer_shell_v1` surfaces
- [x] Compose Desktop content with state, animation, interactivity
- [x] Frame pacing off `wl_surface.frame` — idle costs nothing (`FrameClock`, `IdleFrameTest`)
- [x] Keyboard through xkbcommon: layout-aware keysyms, modifier state (`KeyboardInput`, `Xkb`)
- [x] Text input via `TextField` with an IME session (`KortexTextInput`)
- [x] HiDPI: per-surface scale detection, physical-pixel rendering, logical↔buffer pointer translation
- [x] Cursor shapes from `Modifier.pointerHoverIcon` (`WlCursorTheme`)
- [x] Configurable layer, anchor, exclusive zone, keyboard mode (`Layer`, `Edge`, `ExclusiveZone`,
      `KeyboardInteractivity`), gathered into `SurfaceConfig`

## Ahead of the reference

- [x] **No helper binary, no socket.** Direct FFM into libwayland; the reference ships a C `wayland-helper`
      and pipes pixels over a Unix socket. Its `BinarySource`, bundled-binary extraction and JVM reflection
      flags have no kortex equivalent and should not get one.
- [x] **No Swing EDT requirement.** kortex runs its own frame dispatcher; the reference mandates
      `SwingUtilities.invokeLater` + `Dispatchers.Swing` or it renders blank frames.
- [x] **Multi-monitor.** `KortexShell` puts a per-output spec on every `wl_output` and tracks hotplug. The
      reference lists single-monitor-only as a known limitation.

## Protocol versions

Every global is bound at the newest version its interface declares; `wl_registry_bind` clamps to what
the compositor offers. No legacy paths, no version-conditional branches, no migration shims.

- [x] **1. Newest bind version, full listener arrays.** `wl_seat` 1 → 11, `wl_output` 2 → 4, `wl_shm`
      2 → 3, `wl_compositor` 6 → 7. Listener arrays grew with them — `wl_pointer` 5 → 12 slots,
      `wl_output` 4 → 6, `wl_keyboard` 5 → 6, `wl_seat` 1 → 2 — because libwayland indexes a listener
      array by event opcode and calls straight through an empty slot. (`ProtocolVersionTest`)
- [x] **2. Per-surface scale** from `wl_surface.preferred_buffer_scale` (compositor v6). `WlOutput.Handle`
      and `WlOutput.detectScale`, which guessed one scale across every output, are gone. Hyprland answers
      `get_layer_surface` with the event, so the first frame already has it — and nothing depends on that
      ordering, since `maybeRescale` runs on every loop tick. (`SurfaceScaleTest`)
- [x] **3. Output geometry** — position, transform, `mode` width/height (current-flagged only), `name`,
      `description` and `scale`, accumulated into pending fields and published atomically on `done`, and
      reachable per surface through `KortexShell`. (`OutputGeometryTest`)
- [x] **4. Key repeat** from `wl_keyboard.repeat_info`. `KeyboardInput` tracks the held key and its
      due time, delivered through `KortexSurface`'s existing tick (`reconcile`, on the loop thread) rather
      than a timer thread, so a repeat travels the same `KortexTextInput` path a real press does.
      (`KeyRepeatTest`)
- [x] **5. Explicit width and `set_margin`.** `width` and a `Margins(top, right, bottom, left)` type in
      the protocol's wire order reach `set_size`/`set_margin`. Omitting either dimension without both of
      that axis's edges anchored returns `KortexError.UnspannableAxis` instead of taking the connection
      down. (`LayerGeometryTest`)
- [x] **6. `exclusiveZone = -1` and `set_exclusive_edge`.** `set_exclusive_edge` (opcode 9, since v5) now
      reaches the wire; `-1` reserves nothing and extends a surface all the way to its anchored edges
      instead of yielding to other surfaces' exclusive zones. (`ExclusiveZoneTest`)

Next: the polish and housekeeping items below; the surface presets and the Foundations they depended on
are done.

## Foundations

- [x] **Output geometry.** `OutputListener` publishes position, transform, mode size, name, description
      and scale on `done` (`WlOutput.kt`), reachable through `KortexShell.activeSurfaces` and
      `ActiveSurface.geometry` — both public, so a host can read an output's logical size and hand it to
      `SurfaceConfig.contextMenu`. (`OutputGeometryTest`)
- [x] **A surface handle.** `KortexSurfaceHandle` (`size`, `close()`) and a `LocalKortexSurface`
      composition local, provided by `KortexSurface.setContent` around the caller's content; `compose`
      still knows nothing about wayland. `size` is logical (surface-local) pixels, backed by Compose state
      so a configure recomposes a reader. `close()` posts onto the surface's queue and sets the same flag
      a real `zwlr_layer_surface_v1.closed` would, so `KortexShell.serviceSurfaces` reaps a self-close
      through the one existing teardown path. No `awaitClose()` — the blocking entry point is already the
      host's wait, and it returns once content has closed the last surface. (`SurfaceHandleTest`)
- [x] **Several independent surfaces on one connection.** `runSurfaces(vararg SurfaceSpec)` is the general
      entry point and `runBar` is one spec over it. A `SurfaceSpec` pairs a `SurfaceConfig` with an
      `OutputTarget` — `EveryOutput` for one surface per `wl_output`, following hotplug, `CompositorChoice`
      for a single surface that names no output — and the content to draw on it. `KortexShell` tracks
      outputs and surfaces separately, so a dock, an OSD and a menu run side by side on one connection,
      and `activeSurfaces` hands out each surface already paired with its output's geometry rather than a
      second list parallel by naming convention. Its loop ends when no surface is left and none can
      return, so a host whose content closed itself stops instead of spinning on an empty screen, while an
      `EveryOutput` spec with no output waits for one. (`MultiSurfaceTest`)

## Surface presets

- [x] `SurfaceConfig.panel(edge, thickness, length)` — anchored to `edge` plus the two edges
      perpendicular to it; `thickness` is both the surface's extent perpendicular to `edge` and exactly
      what it reserves, `length` runs along `edge` and 0 spans it. `runBar` is rebuilt on it. Still wants
      `ContentPosition`, a Compose-side layout concern. (`SurfacePresetTest`)
- [x] `SurfaceConfig.dock(edge, thickness, length)` — `panel` with `OnDemand` keyboard. (`SurfacePresetTest`)
- [x] `SurfaceConfig.desktopBackground()` — `Layer.Background`, anchored to all four edges, with
      `ExclusiveZone.Overlap` so it reserves nothing and is never displaced by a panel's zone.
      (`SurfacePresetTest`)
- [x] `SurfaceConfig.osd(width, height)` — floating, centred on the output by anchoring nothing, sized
      exactly `width` by `height`. Anchoring nothing forces `ExclusiveZone.Yield`, because `Overlap`
      extends a surface to its anchored edges and one with no anchor has nothing to extend to: Hyprland
      lists such a surface in `hyprctl layers` and draws nothing. The cost is that a yielding OSD is
      centred in the *usable* area, so another surface's own exclusive zone can push it off true centre.
      A preset that must sit dead centre has to anchor and place itself with margins, which also lets it
      `Overlap`. (`SurfacePresetTest`)
- [x] `SurfaceConfig.appMenu(width, height)` — an `osd` that also takes keyboard focus on demand, for a
      floating panel whose content dismisses it through its `KortexSurfaceHandle`. (`SurfacePresetTest`)
- [x] `SurfaceConfig.contextMenu(at, menuSize, outputSize)` — places a menu so its top-left corner
      sits at `at`, flipping to whichever corner keeps it inside `outputSize`,
      independently per axis. A pure function of its three inputs, so the flip logic needs no compositor
      to test. A menu wider or taller than `outputSize` still flips on that axis: the anchored corner
      sits at `at` and the excess runs off the opposite edge, so the answer stays one consistent corner
      rather than a special case. It carries `ExclusiveZone.Overlap`, which is what makes `at` and
      `outputSize` output coordinates: a yielding menu is anchored and margined inside whatever the
      surfaces that reserve space leave over, so a bar's zone displaces it by that bar's thickness.
      A corner anchor is two perpendicular edges, so `Overlap` has edges to extend to and the explicit
      size survives — the restriction that forces `osd` onto `Yield` does not reach here.
      (`MenuAnchorTest` for the flip, `SurfacePresetTest` for the coordinate space)
- [x] `SurfaceConfig.lockScreen()` — `Layer.Overlay` with `KeyboardInteractivity.Exclusive`, anchored to
      all four edges with `ExclusiveZone.Overlap`. Not a real lock: kortex binds no `ext-session-lock-v1`.
      (`SurfacePresetTest`)
- [x] The escape hatch is `SurfaceConfig`'s own constructor: layer, anchor, exclusive zone, keyboard,
      size, margins, namespace and exclusiveEdge are all public and defaulted where a default makes
      sense, so a caller a preset doesn't cover constructs one directly. (`SurfaceConfigTest`)

## Polish

- [ ] **Cursor shapes.** kortex maps 4 (`Default`, `Crosshair`, `Text`, `Hand`); the reference names 10 —
      missing `move`, `wait`, and the four resize shapes. Blocked by Compose: only `PointerIcon.Default`,
      `.Crosshair`, `.Text` and `.Hand` are public constants, so the rest need a caller-supplied cursor
      path through `KortexPlatform`.
- [ ] **Per-surface density override.** The reference takes `density = Density(2f)` and reads
      `GDK_SCALE`/`QT_SCALE_FACTOR`. kortex always uses the surface's `preferred_buffer_scale`; the unused
      `scale` parameter on `KortexSurface.create` was removed as dead, so this would reintroduce it
      deliberately.

## Housekeeping

- [ ] **Move `WlSurfaceListener` out of `LayerShell.kt`.** The config vocabulary has moved to
      `SurfaceConfig.kt`, leaving the `zwlr_layer_shell_v1` tables and `LayerSurface`.
      `WlSurfaceListener` is still the odd one out: `wl_surface` is a core interface, not part of this
      wlroots extension.
- [ ] **Three published statements of "the default bar".** `SurfaceConfig()`'s own defaults
      (`Top+Left+Right`, `height = 32.dp`), `SurfaceConfig.panel(Edge.Top, 32.dp)` and `runBar`'s
      `height = 32.dp` all describe the same surface, so changing one silently disagrees with the other
      two. Folding them means changing a published default, which is why it was left rather than done.
- [ ] **Compose warns `GlobalSnapshotManager: concurrent registrations on multiple threads might lead
      to races`** in the output of any test that runs two surfaces. It comes from Compose, not kortex —
      one `kortex-frame` thread per surface provokes it — and predates the branch, but hosting many
      surfaces on one connection makes it routine rather than rare.
- [ ] **A screenshot pixel reads one channel step off**, roughly 1 run in 18 (`0xFF818080` where
      `0xFF808080` is expected). Not introduced by the protocol work, and seen in both `KortexShellTest` and
      `KortexSurfaceTest`, so it is compositing or capture timing rather than anything test-specific.
      `Screen.settledPixel` already samples until two reads agree, which is evidently not enough.

## Deliberately not doing

- `BinarySource` / bundled binary extraction / arch-specific resources — no helper binary exists.
- The two JVM reflection flags — kortex reaches `PlatformContext` directly.
- JitPack publishing — publishing is out of scope for now.
