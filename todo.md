# Feature parity with OShane-McKenzie/wayland

Tracking against that project's README. It is **GPL-3.0**; kortex is **Apache-2.0**. Those combine in one
direction only: Apache-2.0 code may be taken into a GPLv3 work, never the reverse, so nothing from that
repo can land here. Read it for protocol structure; build from the wlroots XML and the Wayland spec.

## Already there

- [x] `zwlr_layer_shell_v1` surfaces
- [x] Compose Desktop content with state, animation, interactivity
- [x] Frame pacing off `wl_surface.frame`: an idle bar draws nothing (`FrameClock`, `IdleFrameTest`).
      Its event loop sleeps until an event, posted work or a key-repeat deadline needs it (`EventLoopWakeTest`).
- [x] Keyboard through xkbcommon: layout-aware keysyms, modifier state (`KeyboardInput`, `Xkb`)
- [x] Text input via `TextField` with an IME session (`KortexTextInput`)
- [x] HiDPI: per-surface scale detection, physical-pixel rendering, logical↔buffer pointer translation
- [x] Cursor shapes from `Modifier.pointerHoverIcon`, including move, wait and all eight resize directions
      (`WlCursorTheme`)
- [x] Configurable layer, anchor, exclusive zone, keyboard mode (`Layer`, `Edge`, `ExclusiveZone`,
      `KeyboardInteractivity`), each a parameter `LayerSurface` takes, and a change to any of them reaches a
      surface already on screen (`LiveSettingsTest`, `LiveKeyboardTest`)

## Ahead of the reference

- [x] **No helper binary, no socket.** Direct FFM into libwayland; the reference ships a C `wayland-helper`
      and pipes pixels over a Unix socket. Its `BinarySource`, bundled-binary extraction and JVM reflection
      flags have no kortex equivalent and should not get one.
- [x] **No Swing EDT requirement.** Compose runs on the thread that runs kortex's own event loop; the
      reference mandates `SwingUtilities.invokeLater` + `Dispatchers.Swing` or it renders blank frames.
- [x] **Multi-monitor.** `rememberMonitors()` lists every `wl_output` and follows hotplug, so a host shows a
      surface per monitor, keyed by it. The reference lists single-monitor-only as a known limitation.

## Protocol versions

Every global is bound at the newest version its interface declares; `wl_registry_bind` clamps to what
the compositor offers. No legacy paths, no version-conditional branches, no migration shims.

- [x] **1. Newest bind version, full listener arrays.** `wl_seat` 1 → 11, `wl_output` 2 → 4, `wl_shm`
      2 → 3, `wl_compositor` 6 → 7, and the clipboard's `wl_data_device_manager` at 4. Listener arrays grew
      with them, because libwayland indexes a listener array by event opcode and calls straight through an
      empty slot: `wl_pointer` 5 → 12 slots, `wl_output` 4 → 6, `wl_keyboard` 5 → 6, `wl_seat` 1 → 2.
      `wl_data_device_manager` v4 adds only its own `release` request, and no event. The release goes out
      only where a compositor offers v4; Hyprland offers v3, so no test sends it. (`ProtocolVersionTest`;
      `ClipboardTest` for `wl_data_device_manager`)
- [x] **2. Per-surface scale** from `wl_surface.preferred_buffer_scale` (compositor v6). `WlOutput.Handle`
      and `WlOutput.detectScale`, which guessed one scale across every output, are gone. Hyprland answers
      `get_layer_surface` with the event, so the first frame already has it, and nothing depends on that
      ordering, since `maybeRescale` runs on every loop pass. (`SurfaceScaleTest`)
- [x] **3. Output geometry**: position, transform, `mode` width/height (current-flagged only), `name`,
      `description` and `scale`, accumulated into pending fields and published atomically on `done`, and
      reachable through each `Monitor`'s `geometry`. (`OutputGeometryTest`)
- [x] **4. Key repeat** from `wl_keyboard.repeat_info`. `KeyboardInput` tracks the held key and its
      due time, delivered by `reconcile` on the loop thread, which wakes for it, rather than by a timer
      thread, so a repeat travels the same `KortexTextInput` path a real press does.
      (`KeyRepeatTest`)
- [x] **5. Explicit width and `set_margin`.** `width` and a `Margins(top, right, bottom, left)` type in
      the protocol's wire order reach `set_size`/`set_margin`. Omitting either dimension without both of
      that axis's edges anchored returns `KortexError.UnspannableAxis` instead of taking the connection
      down. (`LayerGeometryTest`)
- [x] **6. `exclusiveZone = -1` and `set_exclusive_edge`.** `set_exclusive_edge` (opcode 9, since v5) now
      reaches the wire; `-1` reserves nothing and extends a surface all the way to its anchored edges
      instead of yielding to other surfaces' exclusive zones. (`ExclusiveZoneTest`)

Next: twelve entries are open, and each waits for a decision: under Foundations, AWT's toolkit, which Compose
starts in a scene with a text field; under Surface presets, a fractionally scaled monitor, which measures short;
under Keyboard and clipboard, the character a Ctrl+key types when no layout has an ASCII one on that key, the
clipboard that content inside a Compose `Popup` or `Dialog` reaches, the harness gap that leaves `KeyboardDeliveryTest`
proving only a value-based field, a keymap xkb rejects, images on the clipboard as PNG and JPEG, and drag and
drop; under Housekeeping, the protocol errors libwayland prints to stderr, the Compose error `KortexSceneTest`
prints, the Compose warning `KeyRepeatTest` prints, and a closed surface's `wl_pointer`, which no test sees
outlive it.

## Foundations

- [x] **Output geometry.** `OutputListener` publishes position, transform, mode size, name, description
      and scale on `done` (`WlOutput.kt`), reachable through a shown surface's own `monitor.geometry`. The
      transform is an `OutputTransform`: one of `wl_output.transform`'s eight values, or `Unrecognized` with
      the number the compositor sent, so a value kortex does not know stays typed and nothing throws inside
      the listener. (`OutputGeometryTest`)
- [x] **A surface handle.** `KortexSurfaceHandle` (`size`, `close()`) and a `LocalKortexSurface`
      composition local; `compose` still knows nothing about wayland. A surface's content reaches its own
      surface as its receiver, a `SurfaceScope`, which is one of these handles, and as
      `LocalKortexSurface.current` further down: one handle for the life of the call. `size` is logical
      (surface-local) pixels, backed by Compose state, and a configure recomposes a reader
      (`RecompositionTest`). `close()` asks the shell to end the surface, which it does in its next pass
      through the same reconcile that reaps a surface the compositor has closed. (`SurfaceHandleTest`)
- [x] **Several independent surfaces on one connection.** Each surface call, in `kortexApplication`'s content
      or in another surface's, places a surface of its own on the application's one connection, so a dock, an
      OSD and a menu run side by side, each on its own monitor or the compositor's choice. Closing one surface
      releases the `wl_pointer`, `wl_keyboard` and `wl_seat` it bound before its scene goes, so a sibling on
      the same connection keeps taking input and the closed scene records no crash. `SurfaceTeardownTest` pins
      the sibling's input and the clean scene, but not the release itself, which Hyprland hides (see
      Housekeeping). `KortexSurface.create` is internal, since filling its `wl_output` needs a proxy only this
      module can bind. (`MultiSurfaceTest`, `SurfaceTest`)
- [x] **The rest of that teardown.** `Shm`, `WlCursorTheme` and `WlCursorSurface` each have a `close()`
      now, so the `wl_shm` a surface binds, the second `wl_shm` behind its cursor theme, the
      `wl_compositor` `WlCursorSurface` binds, its cursor `wl_surface` and every `wl_cursor_theme` handle
      are all given back rather than leaked once per surface the shell places and takes down.
      Order carries the risk: `wl_cursor_theme_destroy` destroys the `wl_buffer`s the compositor was
      handed, so the pointer is released and the cursor surface destroyed first, and a round trip proves
      the compositor has processed both before the theme frees them. `rescale` retains each handle it
      supersedes instead of leaking it, since the same argument frees them all. `wl_shm.release` and
      `wl_compositor.release` are sent only when the negotiated version has them, because marshalling an
      opcode past a proxy's version kills the connection; the proxies themselves are always freed
      client-side. Each `close()` is idempotent, since the `KortexSurface` latch guarding them is not
      something a direct caller of those classes has. (`SurfaceLifetimeTest`)
- [x] **Every proxy a surface binds is given back.** `LayerShellSurface.create` binds a
      `wl_compositor` and a `zwlr_layer_shell_v1` of its own, since `WaylandDisplay.require` caches
      nothing, and kept neither; `FrameClock` released its `wl_callback` only when the frame fired, so a
      surface closed mid-frame leaked one. All four are given back now, the shell and the compositor
      after the layer surface and `wl_surface` go, and the callback before the `wl_surface` it was
      requested on. `LayerShellSurface.close()` has the same idempotence latch its siblings have, named
      `disposed` to sit beside `closed`, which says the surface must be torn down rather than that it has
      been. `wl_compositor.release` carries an opcode and a `since` that are fatal to get wrong
      together, so `releaseCompositor` holds both once, beside `releaseShm`. (`SurfaceLifetimeTest`)
- [x] **A surface's FFM upcall stubs are freed with it.** Every object that installs a listener owns an
      `Arena.ofShared()` for its stubs and listener struct, and closes it after destroying the proxy that
      dispatches into them, so no stub outlives its surface and none is freed while a proxy can still
      reach it. `LibWayland.upcall` takes the arena as a required argument, so a new listener cannot fall
      back to the global one by leaving it out. A keyboard surface installs 29 stubs at about 27.8kB of
      RSS each; a create/close loop on one connection grew RSS by about 0.8MB per cycle in a straight
      line before this, and shows no slope after it over 150 cycles.
      What stays in `Arena.global()` does so deliberately: the three library lookups, since closing their
      arena unloads the library under every downcall bound to it, and the `wl_interface` tables, which
      libwayland reads through every proxy made against them. No RSS assertion guards this, because a
      threshold loose enough not to flake would miss one listener put in the wrong arena.
      The half that can crash, a stub freed before its proxy, is covered by the mechanism, each `release()`
      destroying its proxy before it closes the arena, rather than by a test: `SurfaceTeardownTest` passes with
      a pointer's stubs freed before its proxy (see Housekeeping), and whether `SurfaceLifetimeTest` catches it
      is untested.
- [x] **A connection frees what it allocated, and a string a call only reads is freed with the call.**
      `WaylandDisplay` owns the arena its registry listener lives in. `close()` destroys the registry
      proxy, disconnects and then closes that arena, and a first roundtrip that fails in `connect` closes
      the half-built connection the same way. Before, every connection left the listener's struct and
      both stubs in the global arena, and the registry proxy with libwayland. Over 2000 connections the
      JVM's code cache, where the stubs live, grew steadily from 9.3MB to 12.2MB before the change and
      levels off at about 10MB after it. RSS moved too noisily across those runs to confirm or refute the
      27.8kB a stub the entry above measured for surfaces.
      The display name, the memfd name, `$XCURSOR_THEME` and each XCursor name now live in an arena
      confined to the call that reads them: libwayland 1.26 copies the display name into the socket
      address, `wl_cursor_theme_load` and `wl_cursor_theme_get_cursor` read theirs only during the call,
      and the kernel copies the memfd name. `LibWayland.cString` is gone, since the interface tables were
      its last callers and they allocate from the global arena directly. (`WaylandDisplayTest`,
      `WlCursorThemeTest`, `SurfaceCreateFailureTest`)
- [x] **`wl_output.release` is sent.** `ShellOutput.destroy()` calls `releaseOutput`, the same
      `marshalIfSince`-then-`proxyDestroy` shape `releaseCompositor` and `releaseShm` already had, so
      both `removeOutput`'s hotplug path and `close` give every bound `wl_output` back rather than only
      destroying the proxy client-side. Nothing in-process shows a request leaving the client, so the
      covering tests drive `ReleaseOutputProbe` in a child JVM under `WAYLAND_DEBUG=client` and read the
      release requests off its wire: the default build covers the shell-close path, and the hotplug path
      runs once `-Pkortex.hotplugTests=true` opts it in. (`OutputReleaseWireTest`)
- [x] **A surface whose creation fails partway gives back what it built.** `KortexSurface.create` pushes
      a closer for each piece as it builds it, and every exit taken before the `KortexSurface` exists,
      one added later included, runs them newest first from a `finally`. Once the surface is constructed,
      its own `close()` is the one owner of every piece. The pointer check comes before that point, which
      `Seat.bind`'s round trip already allows, so no exit returns an error once the surface exists. The
      test provokes the latest exit this machine can reach, a withdrawn `wl_seat`. The exits for a seat with no
      pointer and for `createFrames`, the cursor theme or the cursor surface failing cannot be reached on this
      machine, and they and the exit for a failed `waitForConfigure` are covered by the mechanism rather than by a
      test. `waitForConfigure` itself returns the connection's error when the connection died before a configure
      came, which `LayerShellSurfaceTest` pins on a connection it ends itself; a live connection that never
      configures, `SurfaceNotConfigured`, is not tested. (`SurfaceCreateFailureTest`, `LayerShellSurfaceTest`)
- [x] **A surface's `monitor.geometry` recomposes a reader.** Content reads its own surface's
      `monitor.geometry` during composition and records every value it composes with. Its first, real
      composition already sees the output's real geometry, since the application round-trips before
      placing. The test then drives it past that with a fabricated event group, called directly on that
      monitor's `OutputListener`, the shell's, from the test thread: `onGeometry`, `onMode` flagged current,
      `onScale`, `onName`, `onDescription`, then `onDone`. That stands in for what a real re-send would
      dispatch on the loop thread. Content recomposes with the fabricated geometry, which needs no real
      output added, removed or changed, so it runs untagged in the default build. (`RecompositionTest`)
- [x] **A shown surface tells its caller each ending once, through its `SurfaceState`.** `kortexApplication` runs the
      host's content as an application composition on the thread that calls it: Compose's `FrameRecomposer` on
      the shell's `LoopQueue`, recomposed in a loop pass once it asks for a frame, over an applier that takes no
      node, so UI placed in it ends the run as `KortexError.ApplicationCrashed`. It runs in the internal
      `KortexShell`, which owns the outputs, clipboard, loop queue and display. A surface is a call to
      `LayerSurface` or to one of the presets: its arguments are its settings and its `content` lambda is what
      it draws. The call queues a placement as it enters composition, and the shell places the surface in its
      next pass, never inside composition, under its namespace as written, on its monitor or the compositor's
      choice. Content reads the surface's size from its first composition on. The call site is the surface's
      identity: the same call keeps its surface however its arguments change, drawing its newest `content` and
      publishing to the newest `state` it composed with, while another surface composable in that place is
      another surface and the first ends as it goes. A `SurfaceState`, remembered at the call site by
      `rememberSurfaceState()` or higher up by the caller, carries a `status` the caller reads in composition:
      `Placing` until the surface reaches the screen, then `OnScreen` with the logical size it is drawn at, read
      from the same value its content reads through its own handle. Every ending sets that status to `Ended`
      once, on the loop thread, after the surface has gone, and names how it ended: `close()` gives
      `Ok(SurfaceEnd.Closed)`, the compositor closing it `Ok(SurfaceEnd.ClosedByCompositor)`, its monitor
      unplugged `Ok(SurfaceEnd.MonitorUnplugged)`, and its call leaving composition
      `Ok(SurfaceEnd.LeftComposition)`; a surface that could not be placed gives `Err` with the `KortexError`
      that stopped it. Content that throws ends only its own surface, with `Err(SurfaceCrashed)` carrying the
      scene's first failure, and so does cleanup that throws as the surface goes, whatever else ended it. An
      ending and a removal in one pass keep the ending, and a call taken out after its surface ended changes
      nothing more. The state it leaves behind holds that ending for its caller to read, and the call that puts
      the surface back takes the same state to `Placing` and on as its new surface is placed.
      `close()` acts on the call's own surface, from any thread, the first call deciding; once it has ended it
      does nothing. `exitApplication()`, from any thread and more than once, ends the run, and closing the shell
      takes every surface call out, each ending as `LeftComposition` unless its content throws as it goes.
      kortex calls no host code on its loop thread, so no surface ending can make the host throw there; the
      host's own content throwing ends the run as `ApplicationCrashed`, and nothing more is placed. Nothing
      else ends the run: an application with nothing on screen keeps running. An application whose
      compositor lacks `wl_compositor`, `wl_shm` or `zwlr_layer_shell_v1` fails as it starts,
      with `MissingGlobal`. `rememberMonitors()`, in the application's content or a surface's, is snapshot state
      listing a `Monitor` for each bound `wl_output` once the round trip after its bind has brought its first
      `done`, and dropping it as its global is removed. A `Monitor` is equal by the output it stands for; its
      `name` is `wl_output.name` and its `geometry` the output's own snapshot state, so a new mode makes no new
      monitor. `LayerSurface`'s `monitor` puts a surface on that output's own `wl_output`, under its namespace as
      written, and a changed one moves the content to a layer surface on the new output, ending nothing. A
      surface asked for on a monitor that has gone ends as `MonitorUnplugged` and is never placed, and one on a
      monitor whose global is removed ends the same way, whether or not the compositor closes it. A
      surface's content reaches its own surface as its receiver and as `LocalKortexSurface.current`, the typed
      clipboard as `LocalKortexClipboard.current` and the shell, so a surface call there places a surface of its
      own; a surface that ends, removed or crashed, takes the surfaces its content showed with it, each ending
      as `LeftComposition`. A connection that dies under the run ends it with the connection's error, and every
      surface still shown ends with that same error; the test ends only its own
      connection, with a bind of a global the compositor never advertised.
      Three paths are covered by reading rather than by a test: a shown surface whose tick fails, which ends as
      `Failed`; that startup check's call, whose check itself is tested; and a changed `monitor`, which this
      desktop's single output leaves nothing to move a surface to. A monitor plugged in while the application
      runs, listed after the round trip that follows its bind, is tested only by `@Hotplug` tests, which run
      once `-Pkortex.hotplugTests=true` opts them in.
      (`SurfaceTest`, which pins `Closed`, `LeftComposition` and a dying connection's error, and
      `ClosedByCompositor` with `CompositorChoiceTest`; `MonitorTest`, which pins `MonitorUnplugged` on both of
      its paths; and `KortexShellTest` and `MultiSurfaceTest` for a monitor plugged in)
- [ ] **Compose starts AWT's toolkit in a scene with a text field.** `-Xlog:class+load` shows
      `sun.awt.X11.XToolkit` loading in a scene with a text field whether or not anything touches the
      clipboard, and before `ComposeClipboard` loads when something does, so the clipboard does not start it.
      Compose's `RectManager` schedules its debounced layout-rect callbacks through `postDelayed`, which launches
      each on Skiko's `MainUIDispatcher` (`Actuals.skiko.kt:30`, `Actuals.desktop.kt:22-23`), Swing's event
      queue: the classes loaded just before `XToolkit` are that path's, from `postDelayed` through
      `SwingDispatcher`, `EventQueue` and `Toolkit`. Those callbacks run on AWT's event thread, not the loop's.
- [x] **Nothing of an ended surface's content runs on kortex's loop after it has ended.** A scene's close
      drains the work that content has queued and then closes its `SurfaceWork`, and `LoopQueue` neither takes
      nor runs anything under an owner that has closed, so what that work throws is recorded before its surface's
      ending is published, and a suspending cleanup, such as a `delay` in a `NonCancellable` `finally`, stops at
      its wait. A cleanup that has hopped to another dispatcher finishes there, and its return to the loop is
      dropped, throw and all, because running that return would run content whose surface has ended.
      `kortexApplication` returns the run's error, and otherwise what closing returned.
      (`LateFailureTest`, `LoopQueueTest`, `SurfaceCloseCancellationTest`)

## Surface presets

- [x] **The presets are composables a host calls.** `Bar`, `Panel`, `Dock`, `DesktopBackground`, `LockScreen`,
      `Osd`, `AppMenu` and `ContextMenu` (`Presets.kt`) are composable functions, each taking the parameters
      its kind needs plus `namespace`, `state`, `content` and a `monitor`, optional except on `ContextMenu`;
      none takes a setting that would change its kind, such as a panel's anchors or a lock screen's keyboard.
      Each calls `LayerSurface` with the `SurfaceConfig` preset of its kind, so each placement rule below is
      written once, in `SurfaceConfig`'s companion. `PresetTest` checks the settings each preset asks for
      against that rule and shows every preset that takes no keyboard focus; `SurfacePresetTest`, which needs
      the desktop to itself, shows `Dock`, `AppMenu` and `LockScreen`, which take the keyboard as they map.
      (`PresetTest`, `SurfacePresetTest`)
- [x] `Bar`: a panel with every parameter defaulted, along the top edge, 32 dp thick, spanning it and reserving
      32 dp. Its edge, thickness, length, margins and keyboard are its own to set, the keyboard for a bar with a
      text field in it. (`PresetTest`)
- [x] `Panel(edge, thickness, length)`, over `SurfaceConfig.panel`: anchored to `edge` plus the two edges
      perpendicular to it; `thickness` is both the surface's extent perpendicular to `edge` and exactly what it
      reserves, `length` runs along `edge` and 0 spans it. `Bar` is built on the same preset. Still wants
      `ContentPosition`, a Compose-side layout concern. (`PresetTest`, `SurfacePresetTest`)
- [x] `Dock(edge, thickness, length)`, over `SurfaceConfig.dock`: a panel with `OnDemand` keyboard.
      (`PresetTest`, `SurfacePresetTest`)
- [x] `DesktopBackground`, over `SurfaceConfig.desktopBackground()`: `Layer.Background`, anchored to all four
      edges, with `ExclusiveZone.Overlap` so it reserves nothing and is never displaced by a panel's zone.
      (`PresetTest`, `SurfacePresetTest`)
- [x] `Osd(width, height)`, over `SurfaceConfig.osd`: floating, centred on its monitor by anchoring nothing, sized
      exactly `width` by `height`. Anchoring nothing forces `ExclusiveZone.Yield`, because `Overlap` extends a
      surface to its anchored edges and one with no anchor has nothing to extend to: Hyprland lists such a surface
      in `hyprctl layers` and draws nothing. The cost is that a yielding OSD is centred in the *usable* area, so
      another surface's own exclusive zone can push it off true centre. A surface that must sit dead centre has to
      anchor and place itself with margins, which also lets it `Overlap`. (`PresetTest`, `SurfacePresetTest`)
- [x] `AppMenu(width, height)`, over `SurfaceConfig.appMenu`: an osd that also takes keyboard focus on demand, for
      a floating panel whose content dismisses it with `close()`. (`PresetTest`, `SurfacePresetTest`)
- [x] `ContextMenu(at, menuSize)`, over `Popup`: opens a menu of `menuSize` whose top-left corner sits at `at`
      inside the surface whose content called it, a bar's or a window's alike, and stacked above it. The compositor
      places it against the anchor rectangle the popup's positioner carries, so `at` is measured from the parent
      rather than from the monitor, and a menu near the screen's right or bottom edge opens the other way on that
      axis instead, each decided on its own. Called outside a surface's content it fails the application, saying
      where a menu belongs. `SurfaceConfig.contextMenu(at, menuSize, outputSize)`, the layer-shell placement it was
      built on before, is still here and still covered, but nothing calls it any more.
      (`PopupTest`; `MenuAnchorTest` and `SurfacePresetTest` for the layer-shell placement)
- [x] `LockScreen`, over `SurfaceConfig.lockScreen()`: `Layer.Overlay` with `KeyboardInteractivity.Exclusive`,
      anchored to all four edges with `ExclusiveZone.Overlap`. Not a real lock: kortex binds no
      `ext-session-lock-v1`. (`PresetTest`, `SurfacePresetTest`)
- [x] The escape hatch is `LayerSurface` itself, which takes every setting with a default, so a surface no preset
      covers calls it directly. One that asks the compositor to span an axis it has no anchor for is not
      placed, and ends with `Err(KortexError.UnspannableAxis)` in its state. The internal
      `SurfaceConfig` behind it gives its four fields that decide the shape, `anchor`, `width`, `height` and
      `exclusiveZone`, no default, because each is only sensible in the light of the others, so each preset states
      a whole shape. (`SurfaceTest`, `SurfaceConfigTest`)
- [ ] **A fractionally scaled monitor measures short.** `OutputGeometry`'s width and height over its
      `scale` are the monitor's logical size only at a whole-number scale. `wl_output.scale` is an
      integer, and Hyprland rounds a fractional scale up, so at 1.5 the monitor measures a quarter short.
      Nothing in kortex divides that way any more, and `OutputGeometry`'s own KDoc says what the division is
      worth. Open: a true logical size, which needs a protocol kortex does not bind yet, such as
      `zxdg_output_v1`'s `logical_size`.
- [x] **A size that rounds below 0 is rejected before it reaches the compositor.** `Dp.toLogicalPx` rounds
      without clamping, and `set_size`'s `uint` arguments would carry a negative size as one above four
      billion, so `requirePlaceableSize` (`LayerShell.kt`) fails a width or height below 0 as
      `KortexError.NegativeSize`, with the axis and the rounded size, beside its check that an axis left 0 has
      both of its edges anchored. `LayerShellSurface.create` and `setSize` both run it before any request goes
      out, so a size a surface changes to is checked as its first one is. A negative `width` or `height`, a
      preset's negative `length` or a `ContextMenu`'s negative `menuSize` leaves the surface unplaced, and its
      state ends with `Err(NegativeSize)` while the run goes on. A `thickness` below one logical
      pixel is caught as `InvalidExclusiveZone`, because `Bar`, `Panel` and `Dock` reserve it.
      (`LayerGeometryTest`, `SurfaceTest`)

## Raising and changing a surface while the host runs

- [x] **A surface can be raised while the application runs.** A surface call put into content at any time, the
      application's own or another surface's, places its surface in the shell's next pass, and taking the call
      out removes the surface again. So a context menu can be built from the position of a click that has
      already happened, and an OSD raised in answer to an event such as a volume change. A call in a surface's
      content leaves with that surface, crashed or removed, its own surface ending as
      `Ok(SurfaceEnd.LeftComposition)`. (`SurfaceOpenTest`, `SurfaceTest`)
- [x] **A surface follows its call's arguments while it is on screen.** A call whose arguments change keeps its
      surface and its content's state, and ends nothing: `layer`, `anchor`, `width`, `height`, `margins`,
      `exclusiveZone`, `exclusiveEdge` and `keyboard` go to the live layer surface in one commit, only the
      values that changed, `set_anchor` first, since Hyprland validates an exclusive edge against the anchor
      pending as that request arrives. A new size comes back as a `configure`, and the surface draws at the size
      the compositor chose. The whole of the new settings is checked before any of it is sent, so a combination
      Hyprland would answer by dropping the connection never reaches it: the surface ends as `Failed` with the
      reason and the run goes on. A changed `monitor` or `namespace` cannot be sent at all, since
      `get_layer_surface` fixes both, so kortex puts a new layer surface around the same composition: the
      content keeps its state and its running effects, and reads the size it last had until the new configure
      arrives. Only the namespace half is tested: this desktop has one monitor, so a changed `monitor` is
      covered by reading rather than by a test, and takes the identical path from `rebuildsOver` on.
      (`LiveSettingsTest`, `LiveKeyboardTest`, `SurfaceRebuildTest`)
- [x] **A surface can be aimed at a chosen monitor.** `LayerSurface`'s `monitor` puts a surface on the
      `wl_output` behind a `Monitor` that `rememberMonitors()` lists, whose `name` is `wl_output.name`, the same
      string `hyprctl monitors` prints; null leaves the choice to the compositor. A `Monitor` exists only while its
      output is connected, so there is no name to wait for: a surface asked for on a monitor that has gone
      ends as `Ok(SurfaceEnd.MonitorUnplugged)` and is never placed, and one whose monitor is unplugged ends
      the same way, while the surfaces on the other monitors stand. (`MonitorTest`; `NamedOutputTest`, with
      `-Pkortex.hotplugTests=true`)
- [x] **A surface the compositor closes ends as `Ok(SurfaceEnd.ClosedByCompositor)`, and nothing takes its
      place**, whether it was shown in the application's content or in another surface's. Its call then shows
      nothing until the host takes it out of composition and puts it back. The end-to-end trigger, the output
      under a surface left to the compositor's choice going away, is not exercised anywhere:
      `KortexSurface.simulateCompositorClose` stands in for it. (`CompositorChoiceTest`, `SurfaceTest`)

The bar demo (`bar/src/main/kotlin/com/fromwau/kortex/bar/Main.kt`) is the worked example: a `Bar` on each
monitor `rememberMonitors()` lists, 56 dp thick with `OnDemand` keyboard for its text field. Its content sets
the thickness its own call asks for, so one button takes the bar to 96 dp and back. The click count and the typed
text sit behind `remember` in that content, so both stand through the resize, and a readout beside them is the size
the compositor gave the bar. A right click on the bar's own background, not on its buttons or its text field, shows
a `ContextMenu` from the bar's content, just below the bar at the click's x; a second right
click moves it, and picking an item closes it through `close()`. A bar whose content crashes has the crash appended
to the crash log, and a bar that ends, however it ended, has an `Osd` in its place saying so, until a click on it
brings the bar back. The menu is an `xdg_popup` parented to the bar, so its point is measured from the bar itself,
and the bar never has to learn where on the monitor the compositor put it.

## Polish

- [x] **Cursor shapes.** kortex maps all 14 of `java.awt.Cursor`'s predefined types: `Default`, `Crosshair`,
      `Text`, `Hand`, `Move`, `Wait` and all eight resize directions, more than the reference's 10.
      `PointerIcon(java.awt.Cursor(type))` reaches its `KortexCursor` through one `PointerIcon`-keyed lookup
      built once in `KortexScene`, since Compose's `AwtCursor` implements `equals`/`hashCode` by cursor type;
      any other icon, a custom AWT cursor included, resolves to `Default`. `WlCursorTheme.resolve` gives
      each shape a candidate XCursor name, its CSS name, then `left_ptr`. (`KortexSceneTest`,
      `WlCursorThemeTest`)
- [x] **The event loop wakes on demand.** `KortexShell.runEventLoop` sleeps in `WaylandDisplay.awaitWork`
      until a Wayland event arrives, another thread posts work, or a held key's next repeat falls due. It
      does libwayland's read dance itself: events already queued are dispatched and the loop goes round
      again; otherwise it flushes, `poll`s the Wayland fd beside an `eventfd`, reads or cancels, and
      dispatches what arrived. `WaylandDisplay.wake()` counts that `eventfd` up from any thread, and every
      post the loop drains calls it after enqueueing: a surface call entering or leaving composition, `close()`
      on a shown surface, `exitApplication()`, the surface queue that invalidations and cursor
      changes go through, and the `LoopQueue` that carries Compose's coroutine work. `KeyboardInput` reports
      a held key's next repeat, the same deadline its own `checkRepeat` delivers against. A shell waits for
      the earliest of those across its surfaces, rounded up to whole milliseconds for `poll`. With no key
      repeating the loop waits indefinitely, so an idle bar sleeps until something actually happens. A
      roundtrip or dispatch inside a pass makes the next wait return at once, since the events it ran can
      change what that pass already checked. A close that content asks for wakes the loop, which reaps the
      surface before it waits again. Another source, D-Bus or a timerfd, would be one more fd in that `poll`.
      A `close()` from another thread wakes the loop through `requestEnd`'s `wake()`, which is covered by
      reading rather than by a test: no test closes a surface from another thread while the loop sleeps.
      (`EventLoopWakeTest` for an idle loop, a surface call entering composition and coroutine work that keeps
      yielding; `SurfaceTest` for `exitApplication()` from another thread; `KeyRepeatTest`;
      `WaylandDisplayTest`)
- [x] **Content that throws ends its own surface with a typed error, not the process.** The reference's
      gradient, a `Brush.linearGradient` ending at `Offset(Float.MAX_VALUE, Float.MAX_VALUE)`, does throw
      `Can't wrap nullptr` from the desktop Skia kortex draws with. Frames after the first are drawn inside a
      libwayland callback, and an exception escaping one makes the JDK end the process with status 1, past any
      handler the host has. `KortexScene` catches anything content throws, `Error`s included, at every call
      into it, and recomposition and effects through a `CoroutineExceptionHandler`, as a typed
      `ContentFailure`: `Composition`, `KeyInput` or `PointerInput`. A failed scene runs no more content, and
      the shell ends its surface in the next pass, with `Err(SurfaceCrashed)` carrying the scene's first
      failure, once, while the run goes on. Content whose cleanup throws as its surface goes ends it the same
      way, whatever else ended it. A surface that cannot be placed, and a failed shm reallocation on resize,
      end their surface with their own `KortexError` the same way instead of throwing; the failed reallocation
      is covered by reading rather than by a test. (`KortexSceneTest`, `KeyboardDeliveryTest`,
      `ContentFailureTest`, and `SurfaceTest` for a surface that cannot be placed)
- [x] **A state change read only while drawing or placing redraws.** Compose reports a change that
      recomposes nothing through the scene's `invalidateDraw` and `invalidateLayout`, not the recomposer.
      That covers a read only in a `Canvas` draw lambda, a `drawBehind` or `graphicsLayer` block or a
      `Modifier.offset { }` lambda, and the press indication `clickable` draws by default. `KortexScene`
      passes both to `CanvasLayersComposeScene`, and each asks the host for a frame from whichever thread
      noticed the change; `KortexSurface` posts that to its loop. A scene phase ends by asking for a frame
      if it still needs one, so the asks raised inside `setContent` and `render` are dropped: the render
      under way, or the host's first render after `setContent`, is that frame. Content that invalidates
      while a render draws it has missed that frame, so `render` then asks for the next one, as Compose's
      own `SingleComposeSceneRenderingScope` does. An unchanged surface still asks for nothing.
      (`KortexSceneTest`, `InvalidationRenderTest`, `IdleFrameTest`)
- [x] **A wayland-level test for a pointer handler that throws.** `VirtualPointerCrashTest` drives a real
      click through the compositor into `CrashedPointerProbe`, a surface whose `clickable` throws, run in a
      child JVM the way `ContentFailureTest` runs `CrashedSurfaceProbe`, both through one shared
      `runProbe` helper, since a throw escaping a real `wl_pointer` callback would otherwise end the JVM
      running the tests. The click reaches it through `VirtualPointer.clickAt`, the same path
      `VirtualPointerClickTest` drives; the surface ends with `KortexError.SurfaceCrashed` whose failure is
      `ContentFailure.PointerInput`, reaching its own `SurfaceState` once, while the probe's own process exits
      cleanly. Its content closes its own surface if no click ever lands, so a missed click fails the test
      on the probe's own output instead of a kill. (`VirtualPointerCrashTest`)
- [x] **The bar demo logs its crashes.** `Main.kt` reads the `SurfaceState` of the bar, of its context menu and
      of its crash popup, and appends the crash an ending carries, a `KortexError.SurfaceCrashed`, as the crash's
      ISO-8601 instant, namespace and failure kind (`Composition`, `KeyInput` or `PointerInput`), then the
      cause's full stack trace, to
      `$XDG_STATE_HOME/kortex-bar/crash.log`, or `$HOME/.local/state/kortex-bar/crash.log` when
      `XDG_STATE_HOME` is unset, empty or relative, per the XDG Base Directory spec; missing parent
      directories are created as needed. `CrashLog.kt`'s `crashLogPath` is a pure function of the
      environment it is handed, and `appendCrash` catches the write's own failure as a typed
      `CrashLogWriteFailed` rather than throwing it; the demo prints the crash and a write failure to
      stderr instead. `main` prints the run's own error to stderr and exits with status 1, which the test
      suite never runs and is covered by the mechanism rather than by a test. (`CrashLogTest`)

## Keyboard and clipboard

- [x] **A key reaches Compose as one of Compose's own keys.** `Xkb.key` maps the keysym a key has with no
      modifiers onto Compose's named `Key` constants. Ctrl+C is `Key.C`, Ctrl+/ is
      `Key.Slash`, and Shift+1 is `Key.One` with Shift held: AWT names a key, not the character it types,
      and Compose's shortcuts expect that. Keysyms never leave `Xkb`, the FFM layer, so `KeyboardInput` and
      everything above it handle only typed keys. A keysym Compose has no name for arrives as `Key.Unknown`
      and still types through its codepoint. The keypad's navigation keysyms stay unnamed on purpose: at the
      base level they are what a keypad digit is, and a text field would move its caret instead of typing
      the digit. (`KeyboardDeliveryTest`)
- [x] **Page Up, Page Down, Insert and the F-keys reach Compose by name.** `Xkb.composeKey` names
      `Page_Up`, `Page_Down`, `Insert` and `F1` through `F12`, so each reaches content as its own `Key`
      instead of `Key.Unknown`, and a text field's own Page Up, Page Down and Insert handling fires. The
      F-keys index into a table the way `DIGIT_KEYS` and `LETTER_KEYS` do; the keypad's own navigation
      keysyms stay unnamed, since at the base level they are what a keypad digit is.
      (`KeyboardDeliveryTest`)
- [x] **Shortcuts under a layout with no Latin letters.** While the active layout is one such as Cyrillic,
      Greek or Arabic, `Xkb.key` names a key after its base keysym in the keymap's first Latin layout, so
      Ctrl+C under a Cyrillic layout is `Key.C` and a text field's own Ctrl+A and Ctrl+C work. Punctuation
      follows too: under `ru` the slash key types a period and is `Key.Slash`. A layout has Latin letters when
      some key's one base-level keysym is `a` to `z`. `Xkb.stateFromKeymap` works out once per keymap which
      layouts have them and which comes first, and keeps that beside the state in an `XkbState` only `Xkb`
      can read. A key with no single keysym in that layout, Escape among them, keeps its own layout's name.
      What a key types still follows the active layout, so Cyrillic types Cyrillic. A Latin layout keeps its
      own keys: German `ü` stays `Key.Unknown` rather than borrowing US `[`, and AZERTY's `Key.A` is the key
      QWERTY calls Q. With no Latin layout configured, nothing changes. (`LatinFallbackTest`,
      `KeyboardDeliveryTest`)
- [ ] **A Ctrl+key types its character into a text field when no configured layout has an ASCII one on that
      key.** With Ctrl held, libxkbcommon 1.13.2 turns a key's character into a control code only when some
      configured layout has an ASCII character on that key, and then from that one: under `us,ru` with `ru`
      active, Ctrl+Q reports 0x11, and under `us,de` with `de` active, Ctrl+ü reports 0x1B, Ctrl+[, both below
      space and dropped (`KeyboardInput.kt:119`). Otherwise it reports the character itself: under `ru` alone,
      Ctrl+Q reports `й` (U+0439), and under `de` alone, Ctrl+ü reports `ü` (U+00FC). `KeyboardInput.deliverKey`
      commits either, as it commits any printable character of a key the composition did not consume
      (`KeyboardInput.kt:137`). Measured against libxkbcommon directly; no test covers it yet. Open: committing
      nothing while Ctrl is held, say, checked against AltGr under the xkb options in use.
- [x] **Copy and paste in a surface's top-level content go through the Wayland selection, never AWT's
      clipboard.** Each shell binds `wl_data_device_manager` once, asks for v4, takes a `wl_data_device` for a seat
      of its own, and provides Compose's `LocalClipboard` and `LocalClipboardManager` around every surface's
      content. Content outside a Compose `Popup` or `Dialog` that calls either reaches that one clipboard. A copy offers
      UTF-8 under exactly `text/plain;charset=utf-8`, `text/plain`, `UTF8_STRING`, `STRING` and `TEXT`, quoting
      the serial of the latest key, keyboard enter or button, and reads the text out of the entry it is handed
      off the loop thread. `setClipEntry(null)` clears the selection under the same serial, whichever client
      made it, and the text this client set stops being its own at once.
      The clipboard follows keyboard focus, which the protocol ties the selection to: every surface's keyboard
      tells it of its enter, its leave and its release. While none of the shell's keyboards has focus, another
      client's text reads as `NoSelection` and is no text to paste. The clipboard keeps the selection offer
      meanwhile: focus moving between the shell's own surfaces leaves every keyboard before the next one
      enters, and need not bring a new offer. The next selection the compositor sends replaces it. This
      client's own copy reads back from memory, with focus or without, through no pipe. A paste of another
      client's text asks for the first of those types the selection lists and reads it off the loop thread,
      for at most 1000 ms and 16 MiB.
      Content that needs to know why a copy or paste failed calls `LocalKortexClipboard.current`, a
      `KortexClipboard` whose `setText`, `clear` and `readText` return a public, sealed `ClipboardError`:
      `NoSelection`, `NoText`, `NoInputSerial`, `NoClipboard`, `PipeFailed`, `ReadTimedOut` or `TooLarge`.
      Content cannot close that clipboard, and it answers only while its shell runs: once the shell has
      closed, every call throws `IllegalStateException` at once rather than waiting on a loop nothing runs.
      Compose's locals keep Compose's contract over the same clipboard: a failed paste gets no entry, and a
      failed copy does nothing and throws nothing. A value-based `BasicTextField`'s Ctrl+C, Ctrl+X and Ctrl+V
      go through `LocalClipboard`, and so does a state-based one's Ctrl+C and Ctrl+V. The AWT clipboard a text
      field's right-click Paste asks answers from a snapshot and never reads: there is text while this
      client's own copy stands, no offer needed for that, or while the shell has keyboard focus and the
      compositor's last selection offer lists a text type. Its contents are this client's own text, and none
      for another client's, which pastes through `LocalClipboard`. The deprecated `ClipboardManager.getText`
      never waits on a read, since reading another client's text needs the loop it runs on. It answers with
      the text this client set, until another selection or a clear replaces it, and with nothing otherwise.
      The protocol hands a client the selection only while one of its surfaces has keyboard focus, so the
      tests that need it take the keyboard and run only while the desktop is free. (`ClipboardTest`,
      `ComposeClipboardTest` and `InputDeliveryTest`; `KeyboardDeliveryTest` for the value-based field;
      `ClipboardFocusTest`, with the desktop free, for both field kinds, the offered types, a clear's own
      text, focus leaving and focus moving between the shell's surfaces)
- [ ] **`KeyboardDeliveryTest` proves keyboard delivery for a value-based field only.** Its harness drives a
      scene's `render` and key delivery from the test thread but hands the scene a separate single-thread
      executor as its `frameContext`; a real shell's own loop thread does both instead. A
      `BasicTextField(TextFieldState)` put through it fails as multithreaded access to `SnapshotStateObserver`,
      from `FocusTargetNode.invalidateFocus` running on that executor while the test thread drives the scene,
      so typing, the named and modified keys, Page Down and a throwing key handler are proven for a
      value-based field only. `ClipboardFocusTest` proves a state-based field's Ctrl+C and Ctrl+V instead,
      through a real shell whose one loop thread the harness problem does not reach.
- [ ] **A keymap xkb rejects reads as no keymap.** `Xkb.stateFromKeymap` answers it with null, so
      `KeyboardInput` cannot tell a keymap that has not arrived yet from one xkb rejected, and drops every key
      either way. A rejected re-send also discards the last good keymap (`KeyboardInput.kt:62-63`). The keymap
      comes from outside kortex, so its rejection is an expected failure. A keymap that cannot be mapped fares
      worse: `LibC.mmapPrivateRead` (`Shm.kt:96`) fails through `check`, and `onKeymap` calls it
      (`KeyboardInput.kt:60`) inside a libwayland callback that catches nothing, which ends the JVM. Open:
      `stateFromKeymap` and the mapping returning a typed `Result`, with `xkb_state_new` returning NULL failing
      fast through `check`, and what kortex does then: keep the last good keymap, drop keys, or tell the host
      through a `KortexError`.
- [ ] **Content inside a Compose `Popup` or `Dialog` copies and pastes through AWT's clipboard.** kortex's own
      `Popup` is a Wayland surface with a scene of its own, so content in one is under kortex's clipboard; this is
      about Compose's two, which a caller can still reach for. Each runs in a
      scene layer whose own `RootNodeOwner` provides `LocalClipboard` and `LocalClipboardManager` again,
      inside kortex's provider: Compose's `AwtPlatformClipboard` and `AwtClipboardManager`. In Compose 1.12's
      ui sources, `Popup.skiko.kt:489` and `:495`, and `Dialog.skiko.kt:222` and `:240`, put their content in
      a layer from `rememberComposeSceneLayer`, which asks `LocalComposeSceneContext` for one
      (`ComposeSceneLayer.skiko.kt:189-194`). That context is the `CanvasLayersComposeScene` itself
      (`CanvasLayersComposeScene.skiko.kt:126-127`), whose `createLayer` (`:483-488`) builds a layer around a
      `RootNodeOwner` of its own (`:559`) and sets its content there (`:671`), under the clipboards that owner
      creates (`RootNodeOwner.skiko.kt:471-472`). No seam short of reflection or copying Compose code reaches
      it: `PlatformContext` carries no clipboard, `LocalComposeSceneContext` is internal, and
      `CanvasLayersComposeScene` takes no `ComposeSceneContext`. `LocalKortexClipboard.current`, which no
      layer provides again, is still the shell's there. (`ComposeClipboardTest`)
- [ ] **Copy and paste images, as PNG and JPEG.** The clipboard carries text only. A copy offers the five
      text types and nothing else, a selection another client offers only as an image reads as
      `ClipboardError.NoText`, and an image entry handed to `LocalClipboard` leaves the selection as it was
      (`ComposeClipboard.kt`). Wanted: `image/png` and `image/jpeg`, both ways. Open: the typed call content
      reads and writes an image through, an `ImageBitmap` or bytes under a named type, and its error for a
      selection with no image; a size cap of its own, since the 16 MiB cap on a text paste was sized for
      text; and turning Compose's desktop image entry, a `java.awt.Image` inside a `Transferable`, to and
      from those bytes without starting AWT's toolkit.
- [ ] **Drag and drop.** The data device serves the selection only. A drag's offer is given back as soon as
      `enter` names it, `motion`, `leave` and `drop` do nothing (`DataDevice.kt`), and kortex never calls
      `start_drag`, so nothing can be dropped onto a surface and nothing dragged out of one. Wanted: text and
      the PNG and JPEG images above, both ways. Open: how a drop reaches content and how content starts a
      drag, and which of the protocol's actions, copy, move or ask, kortex takes.

## Housekeeping

- [x] **`WlSurfaceListener` has a file of its own.** `wl_surface` is a core interface, not part of the
      wlroots extension, so its listener sits in `WlSurfaceListener.kt` and `LayerShell.kt` keeps the
      `zwlr_layer_shell_v1` tables and `LayerShellSurface`.
- [x] **A shell's surfaces run their Compose work on its loop thread.** `KortexShell` keeps one
      `LoopQueue`, a `CoroutineDispatcher`, and hands it to every `KortexSurface.create` call it makes; a
      bare `KortexSurface` builds one of its own. Every surface's scene dispatches onto it: a dispatch
      enqueues and wakes the loop, and each pass runs what was queued when it began, as `pump` does. So
      composition, effects and the recomposer run on the thread that runs the loop, and each surface composes
      first there too, since the shell places surfaces only in its passes. A pass runs one generation of
      the queue, so while its surface does not render, an effect that keeps yielding leaves the loop a wait
      between yields (`EventLoopWakeTest`). Compose's own frame flush runs only its own scene's yield
      chain, to completion; a surface's close runs only its own scene's, for at most
      `LoopQueue.DRAIN_BOUND_ROUNDS` rounds. A `delay` waits on kotlinx's `DefaultExecutor`, whose
      resume comes back through `dispatch`. Closing a surface runs its own scene's queued work right after
      that scene closes. Its recomposer leaves Compose's process-wide snapshot observers before the close
      returns, and a scene whose cancellation ends within the bound has finished cancelling by then. A
      create that fails once its scene exists unwinds the same way. What a closed surface's effects
      dispatch later, such as a `finally` that suspends, still runs while its shell does. The shell drains
      the queue once more as it closes, in rounds under the same bound, running what has reached the queue
      by then; what arrives later, or outlasts the bound, never runs.
      (`EffectsOnLoopThreadTest`, `SurfaceCloseCancellationTest`, `SurfaceCreateFailureTest`)
- [x] **Compose's snapshot pump runs on one thread, for a shell created on the thread that runs it.**
      `GlobalSnapshotManager` prints `concurrent registrations on multiple threads might lead to races`
      (b/418800424) when the snapshot pumps its surfaces register run on different threads.
      `FrameRecomposer.performFrameDispatch` flushes a surface's pending coroutine work, its pump included,
      on the loop thread that renders it, and every other path that work takes reaches the same thread
      through the loop's queue. A pump first runs on the thread that creates its surface or composition, so
      this holds when `KortexShell.createApplication` and `runEventLoop` share a thread, as in `kortexApplication`.
      The tests assert it prints nothing while two surfaces run their effects on that thread, under
      `pump` and under a real loop, and while content shows a surface mid-run. (`EffectsOnLoopThreadTest`,
      `EventLoopWakeTest`)
- [x] **A surface's close runs only its own scene's queued work.** Each `KortexSurface` puts a
      `SurfaceWork` element of its own into its scene's frame context. Every dispatch that scene makes
      reaches `LoopQueue` carrying it, whether through Compose's trampoline or frame dispatcher or as a
      `delay`'s resume, and the queue keeps it with the work. Closing a surface, and a create that unwinds
      once its scene exists, run only the work carrying that surface's element, in queue order and
      including what it queues in turn, a round at a time, each round what was queued as it began. They
      stop once a round finds none, or once `LoopQueue.DRAIN_BOUND_ROUNDS` rounds have run. Every other
      surface's work stays queued in its order for the loop's next pass, so a sibling whose content keeps
      yielding holds neither that close, nor the loop thread, nor `KortexShell.close()`. Neither does a
      closing scene whose own cleanup keeps queuing work, such as a `NonCancellable` spin in a `finally`.
      Its close returns once the bound's rounds have run, the shell's passes run the rest beside every
      other surface's work, and the shell's final drain stops at the same bound.
      (`LoopQueueTest`, `SurfaceCloseCancellationTest`, `SurfaceCreateFailureTest`)
- [x] **Seven tests across six classes hotplug an output, and the default build leaves them out.**
      `@Hotplug` (`Hotplug.kt`, `wayland/src/jvmTest/kotlin/com/fromwau/kortex/wayland`) tags every test
      in `KortexShellTest`, `MultiSurfaceTest`, `NamedOutputTest`, `OutputHotplugTest`,
      `OutputReleaseWireTest` and `SurfaceScaleTest` that reaches `Hyprctl.createHeadlessOutput`, and
      `settings.gradle.kts` excludes the tag from every `Test` task unless `-Pkortex.hotplugTests=true`,
      which also sets the `kortex.hotplugTests` system property.
      Outside Gradle, IntelliJ's own JUnit runner included, the tagged tests are reported disabled unless
      that system property is `true`.
      That opt-in still hotplugs the live desktop the tests run on: Hyprland (0.56.2) re-sends dmabuf
      feedback to every client on every output added or removed, and GTK 4.22.4 crashes on a re-send
      roughly one time in 256 (fixed in 4.22.5); Steam's X11 GTK2 crashed too, on X errors about a RandR
      output that no longer existed. Test runs on the live desktop took down ghostty, AGS and Steam.
      GTK's NEWS lists the fix: 4.22.5 unmaps the right pointer in the dmabuf format table (!10166,
      !10305), and the desktop has run 4.22.5 since 2026-09-10. An opt-in run on it at `36abdb7` passed
      all 117 wayland tests with ghostty, AGS and Firefox open, and none of them crashed; Steam was not
      running. One run has too few re-sends to show a one-in-256 crash gone, so it backs the fix without
      proving it.
      The pointer and screenshot tests still need a desktop nobody is using regardless of the tag:
      `SurfaceLifetimeTest` has clicked into a fullscreen game, and that game's cursor re-centring failed
      `OutputRescaleTest` three times.
      A nested Hyprland was considered, since it would also keep the virtual pointer and the screenshots
      off the user's own session, and set aside in favour of the tag.
- [x] **A screenshot pixel occasionally read a step off** an expected `0xFF808080`, in `KortexShellTest`,
      `KortexSurfaceTest` and `MultiSurfaceTest`, at times two full-suite runs in three. It was never
      compositing noise. Hyprland ramps a new layer surface from whatever is behind it up to its own
      colour over roughly 850ms, one step per frame, and a probe that sampled the centre pixel as fast as
      `grim` allows showed the tail of that ramp is its slowest part: steps 70 to 125ms apart, with the
      value wobbling a step either way as it lands. `Screen.settledPixel` slept 90ms and returned as soon
      as two reads agreed, so landing twice on one tail step was likely rather than rare, and every value
      of that kind ever recorded, `0xFF7E7E7E` through `0xFF828181`, is a point on that ramp.
      A stability window cannot tell a finished surface from a step that happens to hold, so
      `Screen.pixelReaching` now polls for the colour each caller expects and hands back the last value
      read if it never arrives; a deliberately wrong expected colour still fails.
      Fourteen full-suite runs under `MALLOC_CHECK_=3` and `MALLOC_PERTURB_=165` then failed once, and
      the value, `0xFF3B7891`, held for the whole five-second budget while matching no ramp step, no
      theme colour and neither window border: something was drawn over the point. That run coincided
      with an AGS media popover being opened from the bar; it hangs from the bar's right-hand end, so
      whether it reached the sample point at the screen's centre is not confirmed. The failure captured
      before the change, `0xFF101417`, names no single source: it is the theme's `#0f1417`, which the
      terminal background, the inactive window border, AGS and dunst all draw, so it is what shows
      through wherever nothing sits on top, and it has not recurred since. These tests read the
      composited screen, so a run needs a desktop nobody opens anything on.
- [x] **`OutputRescaleTest`'s pointer case occasionally landed off its aim**, `Offset(80.0, 16.0)`, in
      four runs at two monitor modes. Every pointer device on the seat moves the one cursor, and the test
      kept the last position the bar reported while the cursor sat on it for half a second. All four
      landing points, `Offset(14.0, 62.0)`, `Offset(144.0, 0.0)`, `Offset(34.0, 62.0)` and
      `Offset(0.0, 58.0)`, lie on an edge at scale 2: the bar's bottom row twice, its top row, and the
      screen's left edge, where a cursor stops. Each is where a cursor another device carries off the bar
      last touches it. The desktop these ran on sets `force_no_accel`, so a mouse moves the cursor in whole
      device counts, which fits every point being a whole logical pixel. A cursor log settled it.
      Of 20 repetitions, the one that failed, at `Offset(100.0, 2.0)`, did so as a hand dragged the cursor
      up out of the bar. Twenty more with the mouse untouched passed, with only the test's own two points
      on the log. A stale surface named `kortex` in `hyprctl layers` was not the cause: it would have put
      three of the four landing points left of the screen edge.
      The test now clears the position right before its own move and keeps the first one after it. A
      second virtual pointer dragging the cursor out of the bar straight after that move failed the old
      version three times in three, on the top row, and passes this one. `ProtocolVersionTest`'s wheel
      case waited half a second on the bar before scrolling, and `SurfaceLifetimeTest` and
      `SurfaceTeardownTest` waited before pressing and again with the button held. The scroll now goes out
      with the move onto the bar, and all three click tests click through `clickAt` (`Screen.kt`), which
      sends the move and both buttons together, so no other device's motion can come between them
      (`OutputRescaleTest`, `ProtocolVersionTest`, `VirtualPointerClickTest`, `SurfaceLifetimeTest`,
      `SurfaceTeardownTest`). Each window is now only as long as delivery takes, so the pointer tests
      still want nobody at the mouse, and a fullscreen game has kept their moves off the bar altogether.
- [ ] **libwayland prints each protocol error to stderr, outside every test's results.** kortex installs no log
      handler, so libwayland's default, `wl_log_stderr_handler` (1.26's `wayland-util.c`), writes a protocol
      error to the process's own stderr as the error is read: `wl_registry#2: error 0: global wl_output
      (2147483647) is unavailable` for each connection `killConnection` (`KillConnection.kt`) ends, in
      `LayerShellSurfaceTest` and `SurfaceTest`. Gradle's results record only `System.err`, so the line shows on
      the console and in no test's `system-err`. A host's stderr gets the same line, though the error already
      comes back typed from `WaylandDisplay.requireAlive`. Open: a handler through `wl_log_set_handler_client`,
      whose `void (*)(const char *fmt, va_list args)` an upcall can read only by handing the `va_list` to
      `vsnprintf`, and what it does with the text: drop it, since the typed error carries the same facts, or
      pass it on somewhere a host can reach.
- [ ] **`KortexSceneTest` prints a Compose error on every run.** One test's content throws while recomposing,
      on purpose, and Compose prints its own report of it, "Error was captured in composition." and the
      `IllegalStateException` it caught, 109 lines of the class's `system-err`. `SurfaceTest` keeps the same report
      off its output with `capturingStderr` (`LoopThread.kt`), which the `compose` module's tests have no copy
      of. Open: a helper there, or one test fixture both modules share.
- [ ] **No test sees a closed surface's `wl_pointer` outlive it.** `KortexSurface.close` releases the
      surface's `wl_pointer`, then its `wl_seat`. On Hyprland 0.56.2 a released `wl_seat` leaves the list its
      seat manager sends enters, motion and buttons through (`SeatManager.cpp`'s `SSeatResourceContainer`
      erases it on the seat's destroy event), so that seat's pointer gets none of them again, whatever became
      of it. `SurfaceTeardownTest` passes with `pointerInput?.release()` removed from `close`, and with
      `PointerInput.release` freeing its stubs while the proxy stays alive, the half that crashes a process.
      Open: a desktop-free test that keeps its `wl_seat` bound while it releases a pointer taken from it and
      then clicks, where a pointer whose stubs go before its proxy would take the test worker down; or accept
      it as covered by the order in `release()`.
- [ ] **`KeyRepeatTest` prints Compose's warning about snapshot registrations on two threads.** Its `a shell's
      loop deadline is the earliest key repeat due on any of its surfaces` prints `GlobalSnapshotManager:
      concurrent registrations on multiple threads might lead to races` twice, run alone or with the rest of the
      class. Its `withShellKeyboards` keeps a `KortexScene` on an executor thread, only so each keyboard has a
      scene to deliver to, beside a shell whose registrations run on the test thread, and Compose 1.12 prints
      the warning whenever its registrations have run on more than one thread (`warnIfMultipleThreads` in
      `GlobalSnapshotManager.skiko.kt`). The shell itself keeps every registration on its loop thread. Open: give
      that scene an immediate dispatcher, which Compose does not register at all, or the shell's own loop.

## Deliberately not doing

- `BinarySource` / bundled binary extraction / arch-specific resources: no helper binary exists.
- The two JVM reflection flags: kortex reaches `PlatformContext` directly.
- JitPack publishing: publishing is out of scope for now.
- A per-surface density override. The reference takes `density = Density(2f)` and reads
  `GDK_SCALE`/`QT_SCALE_FACTOR`; kortex takes density from each surface's `preferred_buffer_scale`, so an
  override would only zoom content its dp values already size.
