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
- [x] HiDPI: `wl_output.scale` detection, physical-pixel rendering, logical↔buffer pointer translation
- [x] Cursor shapes from `Modifier.pointerHoverIcon` (`WlCursorTheme`)
- [x] Configurable layer, anchor, exclusive zone, keyboard mode (`Layer`, `Anchor`, `KeyboardInteractivity`)

## Ahead of the reference

- [x] **No helper binary, no socket.** Direct FFM into libwayland; the reference ships a C `wayland-helper`
      and pipes pixels over a Unix socket. Its `BinarySource`, bundled-binary extraction and JVM reflection
      flags have no kortex equivalent and should not get one.
- [x] **No Swing EDT requirement.** kortex runs its own frame dispatcher; the reference mandates
      `SwingUtilities.invokeLater` + `Dispatchers.Swing` or it renders blank frames.
- [x] **Multi-monitor.** `KortexShell` runs one bar per `wl_output` and tracks hotplug. The reference lists
      single-monitor-only as a known limitation.

## Foundations — everything below depends on these

- [ ] **Explicit width.** `LayerSurface.create` takes `height` only and hardcodes width to
      `SPAN_ANCHORED_AXIS` (`LayerShell.kt:169`). A 280×80 centred OSD is unexpressible. Blocks OSD, both
      menus, and custom surfaces.
- [ ] **Margins.** `set_margin` is declared in the interface table (`LayerShell.kt:34`) but never sent —
      there is no `SET_MARGIN` opcode constant and no call site. Needs the constant, a `Margins` type, and
      a parameter on `create`.
- [ ] **Output geometry.** `OutputListener.onMode` discards width/height (`WlOutput.kt`). Needed to centre
      an OSD and to flip a context menu near a screen edge.
- [ ] **A surface handle.** `runBar` blocks and hands the composition nothing. The reference's
      `WaylandBridge` exposes `state`, `actualWidth/Height`, `close()`, `awaitClose()`, plus a
      `LocalWaylandBridge` composition local so content can dismiss itself. An OSD that disappears after
      2 s and a menu that closes on click both need this.
- [ ] **Several independent surfaces on one connection.** `KortexShell` runs the *same* content once per
      output; it cannot host a dock plus an OSD plus a menu at once. Likely a generalisation of
      `KortexShell.serviceBars` from "bars per output" to "surfaces".

## Surface presets

- [ ] `Panel` — top/bottom bar, no keyboard focus. Closest to today's `runBar`; mostly a rename plus
      `ContentPosition`.
- [ ] `Dock` — as Panel but `OnDemand` keyboard and an exclusive zone.
- [ ] `DesktopBackground` — `Layer.Background`, anchored to all four edges, no exclusive zone.
- [ ] `Osd` — floating, centred, no exclusive zone. Needs explicit width + output geometry + handle.
- [ ] `AppMenu` — floating panel with a dismissable handle.
- [ ] `ContextMenu` — positions at the cursor and flips its anchor near screen edges (`MenuAnchor`
      TOP_LEFT/TOP_RIGHT/BOTTOM_LEFT/BOTTOM_RIGHT).
- [ ] `LockScreen` — `Layer.Overlay` with `KeyboardInteractivity.Exclusive`. Both already exist, so this
      is a preset. Note the reference does *not* use `ext-session-lock-v1`, so it is not a real lock.
- [ ] `surface(config)` — the escape hatch taking layer/anchor/zone/keyboard/size/margins/namespace.

## Polish

- [ ] **Cursor shapes.** kortex maps 4 (`Default`, `Crosshair`, `Text`, `Hand`); the reference names 10 —
      missing `move`, `wait`, and the four resize shapes. Blocked by Compose: only `PointerIcon.Default`,
      `.Crosshair`, `.Text` and `.Hand` are public constants, so the rest need a caller-supplied cursor
      path through `KortexPlatform`.
- [ ] **Key repeat.** `KeyboardInput` maps state to KeyDown/KeyUp only; there is no `repeat_info`
      handling. `wl_keyboard.repeat_info` arrives at v4 and `WlVersion.SEAT` is pinned at 1, so this means
      unpinning the seat and filling the extra listener slots.
- [ ] **Per-surface density override.** The reference takes `density = Density(2f)` and reads
      `GDK_SCALE`/`QT_SCALE_FACTOR`. kortex always uses the compositor's `wl_output.scale`; the unused
      `scale` parameter on `KortexBar.create` was removed as dead, so this would reintroduce it deliberately.
- [ ] **`exclusiveZone = -1`.** Passes through today but is neither documented nor tested.
- [ ] **`set_exclusive_edge`.** In the table (v5), never sent.

## Deliberately not doing

- `BinarySource` / bundled binary extraction / arch-specific resources — no helper binary exists.
- The two JVM reflection flags — kortex reaches `PlatformContext` directly.
- JitPack publishing — publishing is out of scope for now.
