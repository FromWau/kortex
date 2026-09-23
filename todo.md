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

Next: eleven entries are open. Seven wait for a decision: under Surface presets, a popup's grab, which it never
takes, the decoration kortex draws none of, and the requests back to the compositor a window makes none of; under
Keyboard and clipboard, a drag out that never starts, which tells the content that asked nothing, a drag's own
failure, which does not stop the content that failed, and the `move` and `ask` a drag out does not offer; under
Housekeeping, the layer-shell menu flip nothing calls. The last two are work rather than a decision, both under
Surface presets: nested popups taken down outermost first, which xdg-shell forbids, and a layer surface's
unbounded wait for its first configure. Two more are work, under Keyboard and clipboard: no test carries a drag
across the wire, and a bare test surface swallows a drag out.

Not yet run: no test below has been run since `Window`, `Dialog` and `Popup` landed. The suite compiles and
nothing in it has met a compositor since, so a test named in parentheses here is cover that exists rather than a
result anyone has seen. That holds for every entry about windows, dialogs and popups, images on the clipboard,
drag and drop, Ctrl and the keymap, a monitor's logical size, and the test harnesses themselves.

## Foundations

- [x] **Output geometry.** `OutputListener` publishes position, transform, mode size, name, description,
      scale and, from the output's own `zxdg_output_v1`, its logical size on `done` (`WlOutput.kt`),
      reachable through a shown surface's own `monitor.geometry`. The
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
- [x] **Compose starts AWT's toolkit in a scene with a text field, and the clipboard is not what starts it.**
      `-Xlog:class+load` shows `sun.awt.X11.XToolkit` loading in a scene with a text field whether or not
      anything touches the clipboard, and before `ComposeClipboard` loads when something does. Compose's
      `RectManager` schedules its debounced layout-rect callbacks through `postDelayed`, which launches each on
      Skiko's `MainUIDispatcher` (`Actuals.skiko.kt:30`, `Actuals.desktop.kt:22-23`), Swing's event queue: the
      classes loaded just before `XToolkit` are that path's, from `postDelayed` through `SwingDispatcher`,
      `EventQueue` and `Toolkit`. Those callbacks run on AWT's event thread, not the loop's. Keeping the toolkit
      out of the process means replacing that dispatcher, which is Skiko's rather than kortex's ("Deliberately
      not doing"). A class-load log is what this rests on; no test reads it.
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
      where a menu belongs. (`PopupTest`)
- [ ] **A popup takes no grab, so only its call leaving composition dismisses it.** kortex never sends
      `xdg_popup.grab`, and `Popup`'s KDoc says a click outside it does not close it. Open: a grab needs a
      `wl_seat` and the serial of the input that opened the popup, both new in `Popup`'s public signature, and
      taking one gives the popup the user's keyboard for as long as it is up, which would put every popup test
      in a session kept free for it.
- [ ] **Nested popups are taken down outermost first, which xdg-shell forbids.** `KortexShell.takeDown` closes
      a slot's surface, and both the reconcile that ends slots and the shell's own close walk `placed` in the
      order surfaces were placed, a parent before the popups opened from its content. Destroying an outer
      `xdg_popup` while a popup opened from it is still alive is `xdg_wm_base.not_the_topmost_popup`
      (`xdg-shell.xml:1285-1286`). Hyprland 0.56.2 checks nothing of the kind, so it is a portability defect
      rather than a live one, and nothing in the tree nests popups; `XdgPopupSurface.popupParent` parents a
      popup to a popup on purpose, so the path is real. Open: closing a slot's own popups innermost first
      before the slot itself, wherever a surface is taken down, as a rebuild already does through
      `KortexShell.endPopupsUnder`.
- [x] `LockScreen`, over `SurfaceConfig.lockScreen()`: `Layer.Overlay` with `KeyboardInteractivity.Exclusive`,
      anchored to all four edges with `ExclusiveZone.Overlap`. Not a real lock: kortex binds no
      `ext-session-lock-v1`. (`PresetTest`, `SurfacePresetTest`)
- [x] The escape hatch is `LayerSurface` itself, which takes every setting with a default, so a surface no preset
      covers calls it directly. One that asks the compositor to span an axis it has no anchor for is not
      placed, and ends with `Err(KortexError.UnspannableAxis)` in its state. The internal
      `SurfaceConfig` behind it gives its four fields that decide the shape, `anchor`, `width`, `height` and
      `exclusiveZone`, no default, because each is only sensible in the light of the others, so each preset states
      a whole shape. (`SurfaceTest`, `SurfaceConfigTest`)
- [x] **A fractionally scaled monitor measures true.** `OutputGeometry` carries `logicalWidth` and
      `logicalHeight`, taken from `zxdg_output_v1`'s `logical_size`: exact where the mode over the integer
      `wl_output.scale` is a quarter short at 1.5, and already turned where the mode is not. The shell binds
      `zxdg_output_manager_v1` at version 3 and takes a `zxdg_output_v1` per output before the round trip that
      waits for that output's first `done`, so its logical position and size are published under the same
      `wl_output.done` as the mode and nothing reads half an update. Only `logical_position` and `logical_size`
      are read: at version 3 the interface's own `done`, `name` and `description` are deprecated in favour of
      `wl_output`'s, and their listener slots are filled but keep nothing. A compositor that describes no output
      this way still measures each one by its mode over its scale. This desktop's one monitor is at scale 1,
      where those two numbers are the same, so a fabricated 1.5 is what separates them.
      (`OutputGeometryTest`)
- [x] **A size that rounds below 0 is rejected before it reaches the compositor.** `Dp.toLogicalPx` rounds
      without clamping, and `set_size`'s `uint` arguments would carry a negative size as one above four
      billion, so `requirePlaceableSize` (`LayerShell.kt`) fails a width or height below 0 as
      `KortexError.NegativeSize`, with the axis and the rounded size, beside its check that an axis left 0 has
      both of its edges anchored. `LayerShellSurface.create` and `setSize` both run it before any request goes
      out, so a size a surface changes to is checked as its first one is. A negative `width` or `height`, or a
      preset's negative `length`, leaves the surface unplaced, and its state ends with `Err(NegativeSize)`
      while the run goes on. A `thickness` below one logical pixel is caught as `InvalidExclusiveZone`, because
      `Bar`, `Panel` and `Dock` reserve it. `Window`, `Dialog` and `Popup`, `ContextMenu` included, take a
      different route: a size below one pixel is a `require`, so it ends the composition the call sits in rather
      than the one surface it asked for, which is the application for a call in its own content and the surface
      holding the call otherwise, where every popup's lands.
      (`LayerGeometryTest`, `SurfaceTest`, `SurfaceSizeGuardTest`)
- [ ] **kortex draws no decoration of its own, so a window a compositor will not decorate does not open.**
      A window asks `zxdg_decoration_manager_v1` for server side and reads the answer; a compositor that answers
      client side, or says nothing, or advertises no decoration manager at all, ends the window with
      `Err(KortexError.ClientSideDecorationRequired)` rather than putting a window on screen with no title bar to
      move it by. Hyprland 0.56.2 answers server side to every ask and to `unset_mode`
      (`XDGDecoration.cpp:19`, `:27`, `:40`), so nothing here can take that branch and it is covered by reading.
      Open: a title bar, its theme, its buttons and eight resize edges, which is a body of work of its own, for
      the day a compositor that needs them becomes a target.
- [ ] **Content reads a window's states and asks the compositor for none of them.** `WindowState` publishes the
      `maximized`, `fullscreen`, `tiled` and `activated` every `xdg_toplevel.configure` carries, and there is no
      call to maximize, fullscreen or minimize a window, none to move or resize one, and none to raise it. Every
      setting a kortex surface has is one its caller states and kortex sends. Open: a request back is a different
      shape, one the compositor answers when it likes and may refuse, and whether kortex takes that on is
      undecided.

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
      arrives. A popup open in that content cannot come along, since the protocol fixes a popup's parent as the
      popup is created, so the rebuild ends every popup under it as `Ok(SurfaceEnd.LeftComposition)`, innermost
      first and before the old surface is destroyed, while the popup's own call stands. Only the namespace half
      is tested: this desktop has one monitor, so a changed `monitor` is covered by reading rather than by a
      test, and takes the identical path from `rebuildsOver` on.
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
click moves it, and picking an item closes it through `close()`; nothing else takes it away, a click on another
window included. Another button opens a `Window` from the bar's content, with a click counter behind `remember`
and a readout of the size the compositor gave it, and the same button takes it away again. A close the compositor
asks for, its own title bar's button say, opens a `Dialog` on that window instead of closing it: "keep it" calls
`declineClose()`, so the next ask is seen as one, and "close it" takes the window's call out of composition. A bar
whose content crashes has the crash appended to the crash log, and so do the window, the dialog and the menu; a bar
that ends, however it ended, has an `Osd` in its place saying so, until a click on it brings the bar back. The menu
is an `xdg_popup` parented to the bar, so its point is measured from the bar itself, and the bar never has to learn
where on the monitor the compositor put it.

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
- [x] **Ctrl commits nothing into a text field, whatever the active layout puts on the key.** libxkbcommon
      1.13.2 turns a key's character into a control code only where some configured layout has an ASCII one on
      that key, and then from that one: under `us,ru` with `ru` active, the key `us` calls `;` reports `;`,
      and under `ru` alone it reports `ж` (U+0436). `KeyboardInput` commits neither, nor `us`'s own `;`
      under `us` alone. A printable character reaches the open text-input session only with Ctrl up, or where
      it is the character the key's own level 3 carries (`Xkb.typesAltGrCharacter`). Level 3 is AltGr's, and
      Control cannot cancel it: `FOUR_LEVEL`'s modifiers are Shift and LevelThree
      (`/usr/share/X11/xkb/types/extra:8-17`), so Ctrl and AltGr held together still type AltGr's character.
      The level alone does not settle it, since the borrowed ASCII arrives there as anywhere: under `us,de`
      with `de` active, Ctrl+AltGr reports `;` on the `ö` key, whose level 3 is a dead key, and `,` on the
      comma key, whose level 3 is `·`. (`CtrlKeyTest`)
- [x] **Copy and paste in a surface's top-level content go through the Wayland selection, never AWT's
      clipboard.** Each shell binds `wl_data_device_manager` once, asks for v4, takes a `wl_data_device` for a seat
      of its own, and provides Compose's `LocalClipboard` and `LocalClipboardManager` around every surface's
      content. Content outside a Compose `Popup` or `Dialog` that calls either reaches that one clipboard. A copy
      offers UTF-8 under exactly `text/plain;charset=utf-8`, `text/plain`, `UTF8_STRING`, `STRING` and `TEXT`, quoting
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
- [x] **A test scene runs content on the thread that renders it, so both kinds of text field are testable.**
      `onScene` (`DrivenScene.kt`) gives every scene a test drives the one thread a shell gives its own, instead
      of the separate executor that made a `BasicTextField(TextFieldState)` fail as multithreaded access to
      `SnapshotStateObserver`, so a state-based field has a typing case beside the value-based one.
      `ClipboardFocusTest`, with the desktop free, covers a state-based field's Ctrl+C and Ctrl+V through a real
      shell. What the new case is worth, and what every harness on `onScene` is worth, waits on a run none of
      them has had. (`KeyboardDeliveryTest`, `KeyRepeatTest`, `DragAndDropTest`, `KeymapFailureTest`)
- [x] **A keymap kortex cannot use costs the keymap in effect nothing.** `Xkb.stateFromKeymap` answers a
      keymap text xkb rejects with its own `UnusableKeymap`, never `KortexError`, since no public entry point
      of `:wayland` can ever observe it; `LibC.mmapPrivateRead` answers a mapping failure with the generic
      `KortexError.ShmAllocationFailed(ShmStep.Mmap)`, the same case `mmapShared` uses for every other
      mapping. `onKeymap` keeps the keymap it last compiled and the keys named under it, and a keymap no
      mapping can honour is refused inside the libwayland callback that catches nothing rather than ending
      the JVM. Until a keymap kortex can use arrives there is nothing to interpret a keycode with, so keys
      are dropped. A
      compiled keymap `xkb_state_new` answers with no state stays a `check`: that one is xkb handed something
      impossible, not a compositor's doing. The refusal reaches no host, since a compositor that sends an
      unusable keymap is a broken compositor and kortex has no channel for one. (`KeymapFailureTest`)
- [x] **Content that copies and pastes reaches the desktop's clipboard, inside a Compose `Popup` or `Dialog`
      too, where Compose's own `LocalClipboard` is AWT's.** kortex's own `Popup` and `Dialog` are Wayland
      surfaces with scenes of their own, so content in either is under kortex's clipboard; this is about
      Compose's two, which a caller can still reach for. Each runs in a
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
      layer provides again, is still the shell's there, which is where content inside one of Compose's two
      copies and pastes, and `KortexClipboard`'s own doc says which of the two clipboards is which.
      (`ComposeClipboardTest`)
- [x] **Copy and paste images, as PNG and JPEG.** `KortexClipboard.setImage` and `readImage` carry an
      `ImageBitmap` both ways, beside the text calls, and `ClipboardError.NoImage` says the clipboard holds
      nothing either decodes. An image copy offers `image/png` and `image/jpeg`, encoded as the copy is made
      rather than as each send runs, since a receiver waits on the pipe for as long as a send takes; a paste
      asks for the first of those two the selection lists, and this client's own copy decodes from memory with
      focus or without. A text copy still offers the five text types and nothing else. Encoding and decoding
      go through skia, already in the process behind Compose, so nothing on that path names an AWT type and no
      toolkit starts. The cap is its own number, 64 MiB against a text's 16 MiB, since a screenshot of a 4K
      screen is about 33 MiB before anything compresses it; it bounds the transfer, on the way out as on the
      way in, not the image once decoded, and a copy past it fails as `ClipboardError.TooLarge` before the
      compositor is asked for anything. Compose's own clipboard still carries text alone, because its image
      entry is AWT's image type inside a `Transferable`, and reading one starts the toolkit
      (`ComposeClipboard.kt`). (`ClipboardTest`)
- [x] **Drag and drop.** A drag from another application reaches content: `wl_data_device`'s `enter`, `motion`,
      `leave` and `drop` drive the scene's own drop targets (`DataDevice.kt`, `KortexScene.kt`), against the
      surface the drag names and at the scale that surface draws at. The drop hands content a `KortexDragOffer`
      carrying the text and the PNG or JPEG image the drag was offered under, drained off the loop thread before
      content is told of the drop, so no surface waits on the application that let go. Content drags out through
      Compose's own `Modifier.dragAndDropSource`, handing it a `KortexDragSource` as the transferable: the scene's
      `PlatformContext` answers Compose's request for a transfer, and the text or image it carries becomes a
      `wl_data_source` offering the types a copy of the same thing offers, encoded off the loop thread.
      `wl_data_device.start_drag` quotes the input serial the clipboard already keeps, and a drag with none fails
      as `ClipboardError.NoInputSerial` with nothing sent. The source is given back on the loop thread as the
      compositor ends that drag, whether it says so with `cancelled` or with `dnd_finished`, so neither its proxy
      nor the encoded image it holds outlives the drag. `copy` is the only action kortex declares on a source
      or asks for on an offer; since the compositor picks a drop's action from the source's own mask, a drag out
      of kortex is a copy, while a drag into it is whatever its source declared, so content taking one cannot
      assume the source treated it as a copy. (`DragAndDropTest`, `KortexSceneTest`)
- [ ] **A drag out that never starts tells the content that asked nothing.** `KortexShell.startDragFrom` discards
      the `EmptyResult<ClipboardError>` `WaylandClipboard.startDrag` answers with, and `KortexSurface.startDrag`
      drops an encoding that failed past the 64 MiB cap the same way. So content cannot tell a drag the compositor
      was asked for from one that ended as `NoInputSerial`, `NoClipboard` or `TooLarge`: the only answer it gets,
      Compose's `isTransferStarted`, ends the traversal of nested drag sources and promises nothing, and
      `Modifier.dragAndDropSource` has returned by the time any of the three is known. It is documented on
      `KortexDragSource` as a limitation. Open: which channel. Compose's own is
      `DragAndDropTransferData.onTransferCompleted`, an `((DragAndDropTransferAction?) -> Unit)?` whose null
      argument means the gesture did not complete (`DragAndDrop.desktop.kt:55`); honouring it means keeping each
      transfer's data from the request until `wl_data_source.dnd_finished` or `cancelled` says how the drag ended,
      so every drag out needs a session object of its own. A callback on `KortexPlatform` beside `startDrag` is
      the cheaper shape, but it tells the host rather than the content that asked.
- [ ] **A drag's own failure does not stop the content that failed.** `KortexScene.runContent` refuses every later
      call once `firstFailure` is set, and only `record` sets it, from inside the scene. `KortexSurface.startDrag`
      catches an encoding that threw and hands it to `SurfaceScene.contentFailed`, which sets the crash the shell
      reads but never reaches `record`, so the composition keeps rendering and taking input until the next loop
      pass tears the surface down. The teardown is right and bounded to that one pass; what is wrong is that one
      of the two failure routes into a scene skips the gate the other sets. Open: whether `contentFailed` should
      be the single door, which means `KortexScene` publishing a way in, or whether the drag path should reach
      `record` by another route.
- [ ] **A drag out of kortex is a copy and nothing else.** `copy` is the one action kortex declares on a
      `wl_data_source`, and the one it asks for on an offer it takes (`DataDevice.kt`), so content can neither
      drag something out as a move nor let the user choose. Open: `move` means telling the content that dragged
      that the drop happened, so it can remove what left, which is the channel the entry above wants; `ask` means
      answering the compositor mid-drag, once the user has picked an action out of a menu the compositor drives.

- [ ] **A layer surface can wait for its first configure forever.** `LayerShellSurface.waitForConfigure` spins on
      a blocking `display.dispatch()`, so a compositor that never answers leaves the call there with nothing to
      end it. `XdgToplevelSurface` and `XdgPopupSurface` take a sliced dispatch against a four second budget
      instead, and break out when the dispatch reports a dead connection, which is what makes a test mutation
      that removes the configure fail rather than hang. The layer role predates that and was left alone while
      spec C added the other two. Open: give it the same bounded wait, which is the shape `awaitXdgConfigure`
      already holds.

- [ ] **No test carries a drag across the wire, and one attempt got most of the way.** `DragAndDropTest` drives
      the scene directly and never lets a compositor introduce an offer, so `wl_data_device`'s own side has no
      cover. An attempt placed two layer surfaces through a real shell, one a `dragAndDropSource` and one a
      `dragAndDropTarget`, and drove a press, a slop-clearing move, eight motions across the screen and a release
      with a `zwlr_virtual_pointer_v1`. What it established: Compose's gesture fires, `KortexSurface.startDrag`
      encodes off the loop, and `WaylandClipboard.startDrag` answers `Ok`, so **the request reaches the
      compositor with a real serial**. What it did not: the destination surface saw no `onStarted`, `onEntered`
      or `onDrop`, and Hyprland logged nothing about the drag. Untested next step: whether a virtual pointer's
      motion drives `CSeatManager::setPointerFocus` during an active drag the way a real one does, which is what
      sets `dndPointerFocus` and therefore picks the destination (`SeatManager.cpp:288-300`,
      `DataDevice.cpp:666-686`). The attempt is kept at `.superpowers/sdd/run/LiveDragTest.kt.attempt`.
- [ ] **A bare test surface silently swallows a drag out.** `bareSurface` builds its `KortexSurface` without an
      `onStartDrag`, which defaults to a no-op (`KortexSurface.kt:418`), so content that asks to drag out of a
      surface built that way is answered by nothing and the test sees a drag that never happened. Any drag test
      written on that harness passes vacuously. Open: give `bareSurface` the parameter, or make the default
      loud enough that a test cannot mistake it for a working path.

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
- [x] **libwayland says nothing kortex has already said typed.** `WaylandLog` (`LibWayland.kt`) takes the
      client-side log handler through `wl_log_set_handler_client` as `LibWayland` is first touched, reads the
      `va_list` its `void (*)(const char *fmt, va_list args)` carries by handing it to `vsnprintf`, which is the
      only way to read one on this ABI, and writes the line to fd 2 itself, the file descriptor libwayland's own
      default writes to and the one `wl_abort`'s last line has to reach. It drops two formats and no others:
      `display_handle_error`'s, verbatim from libwayland 1.26.0, since `WaylandDisplay.requireAlive` hands the
      host the same facts typed and a second channel for them buys nothing. So `wl_registry#2: error 0: global
      wl_output (2147483647) is unavailable`, one per connection `killConnection` (`KillConnection.kt`) ends in
      `LayerShellSurfaceTest` and `SurfaceTest`, goes no further than the handler. Nothing asserts that: the
      handler writes past `System.err`, so no test's results can hold the line, and a run's own console is where
      its absence shows. No console has been read since.
- [x] **Each module's tests can keep a deliberate throw's report out of their own results.** `capturingStderr`
      has a copy in each test source set, `compose`'s in `CapturingStderr.kt` and `wayland`'s in `LoopThread.kt`,
      each naming the other and saying why the second exists: `wayland` depends on `compose`, so a helper in
      `wayland` cannot flow back, and a shared fixture module for one function that swaps `System.err` costs more
      than the copy. `KortexSceneTest`'s content that throws while recomposing now runs inside it, so Compose's
      own "Error was captured in composition." and the 109 lines after it land in the capture instead of the
      class's `system-err`. Only a run shows the results are clean, and none has been made since.
      (`KortexSceneTest`, `SurfaceTest`)
- [x] **A released `wl_pointer` has a test watching it take no further button.** `PointerReleaseOrderTest` binds
      a `wl_seat` of its own beside a placed surface, takes a pointer from it, clicks the surface once to
      prove that pointer is being dispatched to at all, gives the pointer back while the seat stays bound, and
      clicks again: Hyprland 0.56.2 sends motion and buttons to every `wl_pointer` of every seat resource a
      focused client holds, so a pointer whose stubs went before its proxy would be dispatched through freed
      code and take the test worker down, where `SurfaceTeardownTest` sees nothing because the surface it
      closes gives its own seat back in the same breath. `KortexSurface.close` releases the pointer before the
      seat, and `PointerInput.release` frees its stubs only after the proxy is destroyed. The class needs the
      desktop to itself, since it clicks with a virtual pointer, and has not been run.
- [x] **`KeyRepeatTest` keeps one snapshot registration alive at a time, so Compose has no pair to warn about.**
      Its `withShellKeyboards` kept a `KortexScene` on an executor thread, only so each keyboard had a scene to
      deliver to, beside a shell whose registrations ran on the test thread, and Compose 1.12 prints
      `GlobalSnapshotManager: concurrent registrations on multiple threads might lead to races` as one
      registration starts while another live one sits on a different thread (`warnIfMultipleThreads` in
      `GlobalSnapshotManager.skiko.kt`). That scene is on `onScene` now, whose `Dispatchers.Unconfined` needs no
      dispatch, and Compose keeps a registration only for a dispatcher that does; the shell itself always kept
      every registration on its loop thread. The warning is a `println`, so a class's `system-out` is where its
      absence shows, and none has been read since. (`KeyRepeatTest`)
- [ ] **`SurfaceConfig.contextMenu` and `MenuAnchor` have no caller.** `ContextMenu` opens an `xdg_popup`, whose
      positioner the compositor solves, so nothing in `:wayland`, `:compose` or `:bar` asks for the layer-shell
      corner flip; `MenuAnchorTest`'s eight cases and one of `SurfacePresetTest`'s are all that reach either.
      `OutputGeometry.logicalWidth` and `logicalHeight` are unaffected: they are public API a host reads for its
      own layout, and the flip was only one reader of them. Open: delete the flip, its enum and the nine cases,
      or give it a caller. (`MenuAnchorTest`, `SurfacePresetTest`)

## Deliberately not doing

- `BinarySource` / bundled binary extraction / arch-specific resources: no helper binary exists.
- The two JVM reflection flags: kortex reaches `PlatformContext` directly.
- JitPack publishing: publishing is out of scope for now.
- A per-surface density override. The reference takes `density = Density(2f)` and reads
  `GDK_SCALE`/`QT_SCALE_FACTOR`; kortex takes density from each surface's `preferred_buffer_scale`, so an
  override would only zoom content its dp values already size.
- An image on Compose's own `LocalClipboard`. Its image entry is a `java.awt.Image` inside a `Transferable`, and
  reading one starts AWT's toolkit; the entry kortex provides there carries text alone. Content that copies or
  pastes an image calls `LocalKortexClipboard`, which encodes and decodes through skia.
- Replacing Skiko's `MainUIDispatcher`. It is what runs Compose's debounced layout-rect callbacks on Swing's event
  queue, which is what starts AWT's toolkit in any scene with a text field; the dispatcher is Skiko's, not
  kortex's, and swapping it is a fork of somebody else's frame scheduling.
