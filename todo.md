# Feature parity with OShane-McKenzie/wayland

Tracking against that project's README. It is **GPL-3.0**; kortex is **Apache-2.0**. Those combine in one
direction only — Apache-2.0 code may be taken into a GPLv3 work, never the reverse — so nothing from that
repo can land here. Read it for protocol structure; build from the wlroots XML and the Wayland spec.

## Already there

- [x] `zwlr_layer_shell_v1` surfaces
- [x] Compose Desktop content with state, animation, interactivity
- [x] Frame pacing off `wl_surface.frame` — an idle bar draws nothing (`FrameClock`, `IdleFrameTest`).
      It still wakes on a fixed tick, which is the event-loop item under Polish.
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

Next: the polish and housekeeping items below; the surface presets, the Foundations they depended on, and
raising a surface while the host runs are done.

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
      for a single surface that names no output, created once and not put back should the compositor close
      it — and the content to draw on it. `KortexShell` tracks outputs and surfaces separately, so a
      dock, an OSD and a menu run side by side on one connection, and `activeSurfaces` hands out each
      surface already paired with the spec it came from and its output's geometry rather than a second
      list parallel by naming convention. Closing one surface releases the `wl_pointer`, `wl_keyboard` and
      `wl_seat` it bound before its scene goes, so a sibling on the same connection keeps taking input
      instead of the closed scene taking the process down (`SurfaceTeardownTest`).
      `runSurfaces`, `runBar` and `KortexShell.create` are how a host opens a surface up front, and
      `KortexHost.open` is how its content opens one later. `KortexSurface.create` is internal, since
      filling its `wl_output` needs a proxy only this module can bind. The shell's loop ends when no
      surface is left and none can return, so a host whose content closed itself stops instead of
      spinning on an empty screen, while an `EveryOutput` spec with no output waits for one.
      (`MultiSurfaceTest`)
- [x] **The rest of that teardown.** `Shm`, `WlCursorTheme` and `WlCursorSurface` each have a `close()`
      now, so the `wl_shm` a surface binds, the second `wl_shm` behind its cursor theme, the
      `wl_compositor` `WlCursorSurface` binds, its cursor `wl_surface` and every `wl_cursor_theme` handle
      are all given back rather than leaked once per surface on `KortexShell`'s close-and-replace path.
      Order carries the risk: `wl_cursor_theme_destroy` destroys the `wl_buffer`s the compositor was
      handed, so the pointer is released and the cursor surface destroyed first, and a round trip proves
      the compositor has processed both before the theme frees them. `rescale` retains each handle it
      supersedes instead of leaking it, since the same argument frees them all. `wl_shm.release` and
      `wl_compositor.release` are sent only when the negotiated version has them, because marshalling an
      opcode past a proxy's version kills the connection; the proxies themselves are always freed
      client-side. Each `close()` is idempotent, since the `KortexSurface` latch guarding them is not
      something a direct caller of those classes has. (`SurfaceLifetimeTest`)
- [ ] **`LayerSurface` leaks two more of the same kind.** `LayerSurface.create` binds a `wl_compositor`
      and a `zwlr_layer_shell_v1` of its own, uses each once and keeps neither, so `LayerSurface.close()`
      gives back neither and every surface leaks both on the same close-and-replace path. Found while
      closing the item above, which enumerated the cursor and shm binds but not these. The fix is the
      shape `WlCursorSurface` now has: retain both proxies, then release and destroy them in `close()`
      after the layer surface and `wl_surface` go. `LayerShellProtocol` already declares the shell's
      `destroy` (opcode 1, `since` 3, and it binds at 5), and `LibWayland.marshalIfSince` already carries
      the version rule `wl_compositor.release` needs.

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
- [x] The escape hatch is `SurfaceConfig`'s own constructor: layer, anchor, size, exclusive zone,
      keyboard, margins, namespace and exclusiveEdge are all public, so a caller a preset doesn't cover
      constructs one directly. The four fields that decide the shape — `anchor`, `width`, `height` and
      `exclusiveZone` — have no default, because each is only sensible in the light of the others: a
      caller who omits an anchored axis's extent gets a compile error, and one who asks the compositor
      to span an axis it has no anchor for gets `KortexError.UnspannableAxis`. `runBar` is the one
      published statement of the default bar's shape. (`SurfaceConfigTest`)

## Raising a surface while the host runs

- [x] **A surface can be raised after `runSurfaces` is already running.** `KortexHost`, reached from
      content through `LocalKortexHost.current`, carries `open(spec: SurfaceSpec)`: it places `spec` the
      next time the shell applies pending work and does not retain it, so an output arriving later never
      replays it. This is what makes `osd`, `appMenu` and `contextMenu` reachable: a context menu can now
      be built from the position of a click that has already happened. (`SurfaceOpenTest`)
- [x] **A surface can be aimed at a chosen output.** `OutputTarget.NamedOutput(name)` places a surface on
      the `wl_output` whose `wl_output.name`, the same string `hyprctl monitors` prints, matches `name`.
      The named output not being connected when this is placed is a lost race, not an error: nothing is
      placed and nothing fails, and a standing spec is placed later if the output arrives.
      Hotplug coverage for this rests on a finding checked against the live Hyprland session rather than
      assumed: its headless output names are a monotonically increasing counter that survives removal and
      is never reused, which is what makes the hotplug path deterministic enough to test. A compositor
      whose output names are reused is untested. (`NamedOutputTest`)
- [x] **A `CompositorChoice` surface the compositor takes away is placed again**, as long as an output is
      still connected; content closing its own surface, or a spec placed through `KortexHost.open`, stays
      gone either way. With no output at all connected at that moment there is nowhere to place the
      replacement, and it stays gone until asked for again, and a replacement that fails to be placed is
      dropped rather than ending the run. Two of `CompositorChoiceTest`'s three tests reach this through
      `KortexSurface.simulateCompositorClose`, the shell's own seam: the standing surface being placed
      again, and an opened surface not being replaced. The third, content closing its own surface, does
      not need it. The end-to-end trigger, an output going away under the surface, is not exercised
      anywhere. (`CompositorChoiceTest`)

The bar demo (`bar/src/main/kotlin/com/fromwau/kortex/bar/Main.kt`) is the worked example: a right click on
the bar's own background, not on its button or its text field, opens a `SurfaceConfig.contextMenu` through
`LocalKortexHost.current.open`, targeted at the click's own output with `OutputTarget.NamedOutput` and
anchored just below the bar; a second right click dismisses the menu already open, since `open` hands back
no handle to close one with; and the menu closes itself through `LocalKortexSurface.current.close()` when an
item is picked. It assumes the bar's own top-left is the output's top-left, true only when nothing
else also reserves space on the output's Top edge: `zwlr_layer_shell_v1` reports a surface's size but never
its position, so a bar sharing the Top edge with another exclusive-zone surface has no way to learn how far
down it was actually pushed. Measured against a desktop that runs one: the bar sat at y=62 and its menu
opened at y=56, its own height, which is where the bar would begin if nothing else reserved that edge.

## Polish

- [ ] **Cursor shapes.** kortex maps 4 (`Default`, `Crosshair`, `Text`, `Hand`); the reference names 10 —
      missing `move`, `wait`, and the four resize shapes. Blocked by Compose: only `PointerIcon.Default`,
      `.Crosshair`, `.Text` and `.Hand` are public constants, so the rest need a caller-supplied cursor
      path through `KortexPlatform`.
- [ ] **Wake the event loop on demand instead of on a fixed tick.** Nothing polls Wayland: events arrive
      on a socket fd and `wl_display_dispatch_timeout` blocks on it. What is fixed is the *timeout* —
      `EVENT_LOOP_TIMEOUT_MILLIS = 16`, in both `KortexShell` and `KortexSurface` — so an idle host wakes
      about 60 times a second to run four "has this changed?" checks and draw nothing, which is enough to
      keep a laptop out of deep idle.
      Only one of those checks needs a tick at all. `consumeResize`, `preferredBufferScale` and the retired
      frames are all set by events, so they can be serviced when an event actually arrives.
      `KeyboardInput.checkRepeat` is the exception: it is a deadline (`nowNanos < nextRepeatAtNanos`) and no
      Wayland event announces it, so the constant timeout is standing in for a timer.
      **Small version:** pass the real next deadline as the timeout instead of the constant — no key held
      means blocking until an event arrives, so a genuinely idle bar costs nothing at all.
      That is not safe on its own any more. `KortexHost.open` posts onto `KortexShell.pendingOpens` from a
      frame thread and cannot wake the loop, since only the loop thread may call libwayland; the fixed
      16ms tick is the only reason a menu raised from content appears at all. Blocking on a key-repeat
      deadline instead would leave it unplaced until some unrelated event arrived. That queue therefore
      needs a wakeup of its own, which is the same second-source problem the next paragraph describes.
      **Know before starting it:** `dispatch_timeout` can wait on exactly one source, and blocking inside it
      is being deaf to every other one. A bar that grows a second source — D-Bus for MPRIS, notifications or
      battery, a timerfd, a config watch — needs `wl_display_get_fd` plus the
      `prepare_read`/`read_events`/`cancel_read` dance to put the Wayland fd into its own `poll`/`epoll`
      alongside the others; that dance exists to close the race where one reader decides to sleep just as
      another drains the socket. libwayland 1.26 exports all of it, and FFM reaches it the same way it
      reaches everything else, so nothing here is blocked by the binding layer. That design subsumes the
      key-repeat timer as just another fd, and the small version above does not stand in its way — the
      whole `dispatch_timeout` call is replaced rather than worked around.
- [ ] **Per-surface density override.** The reference takes `density = Density(2f)` and reads
      `GDK_SCALE`/`QT_SCALE_FACTOR`. kortex always uses the surface's `preferred_buffer_scale`; the unused
      `scale` parameter on `KortexSurface.create` was removed as dead, so this would reintroduce it
      deliberately.

## Housekeeping

- [ ] **Move `WlSurfaceListener` out of `LayerShell.kt`.** The config vocabulary has moved to
      `SurfaceConfig.kt`, leaving the `zwlr_layer_shell_v1` tables and `LayerSurface`.
      `WlSurfaceListener` is still the odd one out: `wl_surface` is a core interface, not part of this
      wlroots extension.
- [ ] **Compose warns `GlobalSnapshotManager: concurrent registrations on multiple threads might lead
      to races`** in the output of any test that runs two surfaces. It comes from Compose, not kortex —
      one `kortex-frame` thread per surface provokes it — and predates the branch, but hosting many
      surfaces on one connection makes it routine rather than rare.
- [ ] **A screenshot pixel occasionally reads a step off** an expected `0xFF808080`. It predates the
      protocol work, and it is far more frequent than the earlier estimate of one run in eighteen: on one
      machine it reached both full-suite runs in a row, at the same rate on this branch and on the commit it
      was cut from, so it tracks the machine rather than the code. The drift is small but not one-directional
      and not a fixed shape: `0xFF818080`, `0xFF818180` and `0xFF818181` step one up over a growing run of
      RGB channels, `0xFF828181` steps two up on red, and `0xFF7F7F7F` steps one *down* on all three. Alpha
      has never moved. Anything that reads these as a single pattern is reading too few samples.
      Seen in `KortexShellTest`, `KortexSurfaceTest` and `MultiSurfaceTest`, so it is compositing or capture
      rather than anything test-specific. Ruled out: window occlusion, which would not land this close to the
      expected colour; and a gamma or night-light daemon, since none of `hyprsunset`, `gammastep`, `redshift`
      or `wlsunset` was running when it was checked.
      The open lead is the compositor's own colour management. `hyprctl getoption render:cm_enabled` reports
      true on Hyprland 0.56.2 by default, and a colour-managed render path is exactly the kind of thing that
      rounds a channel by one either way depending on frame timing. Test it by running the failing assertion
      with `hyprctl keyword render:cm_enabled false` and putting it back afterwards, which nobody has done
      yet. Failing that, note that kortex writes `BGRA_8888` into its own buffer while the compared value
      comes out of grim's PNG through `ImageIO`, so compositing, capture and decode all sit in between.
      `Screen.settledPixel` samples until two reads agree, which is evidently not enough.

## Deliberately not doing

- `BinarySource` / bundled binary extraction / arch-specific resources — no helper binary exists.
- The two JVM reflection flags — kortex reaches `PlatformContext` directly.
- JitPack publishing — publishing is out of scope for now.
