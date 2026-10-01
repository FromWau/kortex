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
- [x] **A popup takes no grab, so only its call leaving composition dismisses it.** `ContextMenu` takes one now;
      `Popup` still does not, and that split is the fix rather than a compromise. `xdg_popup`'s own description
      names menus, popovers and tooltips together, and only the first of those should hold the user's keyboard,
      so the grab belongs to the call that says "menu" and not to the one that says "popup". A menu is now
      dismissed by a click away from it, by a key that leaves it, or by the screen locking, and content reads
      that as `Ok(SurfaceEnd.ClosedByCompositor)`.
      **The entry's cost estimate was wrong on the API and right on the keyboard.** No public signature changed:
      the shell already binds a `Seat` per surface and already records every pointer press for `start_drag`,
      whose argument answers the same "the implicit grab a press took" question, so the grab quotes what was
      already there. `XdgPopupSurface.wantsKeyboard` follows the grab, since a grabbing popup is handed the
      keyboard and would otherwise have nowhere to deliver it. The grab is sent after the seat is bound and
      before the first buffer: `xdg_popup.invalid_grab` is "tried to grab after being mapped", and a surface
      committed with no buffer is not yet mapped, so no role had to be built differently.
      A menu opened with no pointer press behind it sends no grab at all, because a denied grab dismisses the
      popup as it arrives, which is worse than the menu that stays up. That is why `PopupTest`'s existing
      `ContextMenu` case, which opens one without a click, still passes unchanged.
      (`PopupGrabWireTest`, which reads the grab off the wire, watches a click away dismiss the menu, and holds
      a plain `Popup` against the same gesture to show it does neither)

- [x] **Nested popups are taken down outermost first.** The code defect is real and the fix is the one below:
      `KortexShell.takeDown` closes a slot's surface without ending the popups under it, and both the reconcile
      that ends slots and the shell's own close walk `placed` parent-before-child. The *reason* first recorded
      here was wrong, and the audit corrected it: destroy-order `xdg_wm_base.not_the_topmost_popup` is enforced
      by nothing for a popup that never took a grab, which is every popup kortex makes -- weston returns early
      without a grab (`libweston/desktop/xdg-shell.c:1437-1444`), mutter's equivalent check sits inside
      `if (seat)`, KWin has it as a literal `// TODO` (`xdgshell.cpp:839-846`), and Hyprland has no such code
      at all. What does fire is mutter's parent-unmap path, which is a client disconnect rather than a
      portability nicety. The defect it pointed at, "Destroying a window while a popup opened from it is
      still up" under "Audit against the reference implementations", is fixed; this entry is kept only so the
      old reasoning is not rediscovered and believed.
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
- [x] **A window's own content can move and resize it now.** `KortexCursor` carried `ResizeNorth` through
      `ResizeNorthWest` and `KortexScene` mapped each one off `Modifier.pointerHoverIcon`, so content on a
      `Window` could put a resize cursor on an edge and nothing could answer the drag that followed: the
      cursor was a promise the protocol side could not keep. `WindowState.askMove` and `WindowState.askResize`
      send `xdg_toplevel.move` and `.resize`. Their entries were already in the hand-built `xdg_toplevel`
      table with the right signatures, `"ou"` and `"ouu"`, so only the opcodes and the calls were missing.
      `ResizeEdge` carries the eight `resize_edge` values, one for each cursor.
      **Each quotes a press on the surface it moves**, which `KortexSurface` keeps for itself as presses pass
      through it rather than reading the shell-wide serial the clipboard holds: a compositor checks the serial
      against the implicit grab that press began, so a press on another surface is the wrong one to name.
      Before the user has pressed anything neither request leaves the client at all, which the wire test reads
      as its own leg, because a compositor refuses one naming no grab by ignoring it and that is invisible
      from this side.
      Nothing to do with decoration, and reachable where kortex already runs: Hyprland 0.56.2 decorates server
      side and a window there can still carry a drag handle in its own content.
      What `xdg_toplevel` still has no opcode for is the entry below. (`WindowDragWireTest`)
- [x] **Content can raise the compositor's own menu for its window.** `WindowState.askWindowMenu` sends
      `xdg_toplevel.show_window_menu` (4), whose table entry was already there as `"ouii"`, so this was an
      opcode and a call like `move` and `resize` before it. It quotes a press on the window's own surface on
      the same terms they do and sends nothing at all before the user has pressed anything.
      `at` is in logical pixels from the window's top-left, which is the window geometry here because nothing
      sends `set_window_geometry`, so the geometry is the whole surface. The menu is the compositor's own, so
      it carries what that desktop offers and matches every other window on screen; a compositor with no such
      menu ignores the ask and nothing reports that, which the public docs say outright.
      Read off the wire beside the other two, including the position: a menu asked for at the transposed point
      is still inside the window, so `PROBE_MENU_AT` is an asymmetric pair and both axes are asserted.
      (`WindowDragWireTest`)
- [x] **A window can ask for size bounds, and reads the bounds it is told.** `WindowState.askMinSize` and
      `askMaxSize` send `set_min_size` (8) and `set_max_size` (7), the last two `xdg_toplevel` requests that
      had no opcode. Their table entries were already there, as `"ii"` twice, as every one of these turned
      out to be.
      **They are the only double-buffered requests kortex sends**, so `sendBuffered` commits the surface where
      it sends them rather than leaving them to apply whenever the window next draws. A window drawing nothing
      may not commit for a long time, and a bound left pending reads exactly like one never asked for.
      `XdgToplevelSurface.send` carried a comment saying none of its requests was double-buffered, which these
      made untrue; it now says so and names the path the two take. The wire test asserts that the request
      immediately after each bound is the commit, because that is the whole of the risk.
      A negative size is clamped to 0. `xdg_toplevel.invalid_size` ends the connection rather than becoming a
      value an ask that returns nothing could hand back, which is the one kind of refusal a caller does not
      get to override.
      `configure_bounds` is read now and reaches `WindowState.recommendedMaxSize`. A 0 on one axis stays a 0,
      since the protocol distinguishes "nothing recommended on this axis" from "nothing recommended at all".
      **Hyprland 0.56.2 sends none of them**, confirmed rather than assumed: a probe run under
      `WAYLAND_DEBUG=client` carries no `configure_bounds` in the window's whole life and reads
      `recommendedMaxSize=null`. So the reader has a desktop-free test, which is the reasoning
      `wm_capabilities` needed too, and the probe prints what it was recommended so that stays checked rather
      than remembered. (`WindowSizeBoundsWireTest`, `ConfigureBoundsTest`)
- [x] **kortex draws no decoration of its own, so a window a compositor will not decorate does not open.**
      A window asks `zxdg_decoration_manager_v1` for server side and reads the answer; a compositor that answers
      client side, or says nothing, or advertises no decoration manager at all, ends the window with
      `Err(KortexError.ClientSideDecorationRequired)` rather than putting a window on screen with no title bar to
      move it by. Hyprland 0.56.2 answers server side to every ask and to `unset_mode`
      (`XDGDecoration.cpp:19`, `:27`, `:40`), so nothing here can take that branch and it is covered by reading.
      **Unreachable twice over now.** Every compositor kortex can start on decorates server side, because the
      layer shell is required and the one mainstream compositor that refuses server-side decoration is GNOME,
      which has no layer shell to start on. Mutter does not answer `client_side`: it advertises no
      `zxdg_decoration_manager_v1` at all, which is the third of the three branches above. sway, labwc and
      KWin advertise it and default to server side; river and Wayfire are unread. So
      `ClientSideDecorationRequired` can now only fire on a compositor that has the layer shell and no
      decoration manager, which is no desktop anyone runs kortex on.
      What is left here is smaller than it was. The eight resize edges are their own entry above. The buttons
      exist: `askMaximized`, `askFullscreen`, `askMinimized` and `declineClose` are public, and
      `wm_capabilities` says which of them the compositor will honour, so content draws its own control and
      calls one. What is genuinely absent is a title bar, meaning a strip that drags the window, and a theme
      for it, and neither is worth building for a compositor that is not in the supported set.
      Decided: keep the clean refusal and draw nothing. A title bar and a theme built for no compositor in
      the supported set is code no desktop exercises, and the refusal already names its own reason where a
      silently undecorated window would not. Reopen it the day a compositor that has the layer shell and no
      decoration manager becomes a target, which would also be the day the branch above stops being covered
      by reading alone.
- [x] **Content reads a window's states and asks the compositor for them too.** `WindowState` publishes the
      `maximized`, `fullscreen`, `tiled` and `activated` every `xdg_toplevel.configure` carries. It began with
      no call to ask for any of them, because every other setting a kortex surface has is one its caller
      states and kortex sends, while a request back is a different shape: one the compositor answers when it
      likes and may refuse. Whether kortex should take that on was the question, and it was taken on.
      `askMaximized`, `askFullscreen` and `askMinimized` came first, with `wm_capabilities` saying which of
      them a compositor will honour, then `askMove`, `askResize`, `askWindowMenu`, `askMinSize` and
      `askMaxSize`. Every one of them is an ask rather than a setting, and the docs say so at each.

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
- [x] **A drag out that never starts tells the content that asked nothing.** `KortexShell.startDragFrom` discards
      the `EmptyResult<ClipboardError>` `WaylandClipboard.startDrag` answers with, and `KortexSurface.startDrag`
      drops an encoding that failed past the 64 MiB cap the same way. So content cannot tell a drag the compositor
      was asked for from one that ended as `NoInputSerial`, `NoClipboard` or `TooLarge`: the only answer it gets,
      Compose's `isTransferStarted`, ends the traversal of nested drag sources and promises nothing, and
      `Modifier.dragAndDropSource` has returned by the time any of the three is known. It is documented on
      `KortexDragSource` as a limitation. The question was which channel. Compose's own is
      `DragAndDropTransferData.onTransferCompleted`, an `((DragAndDropTransferAction?) -> Unit)?` whose null
      argument means the gesture did not complete (`DragAndDrop.desktop.kt:55`); honouring it means keeping each
      transfer's data from the request until `wl_data_source.dnd_finished` or `cancelled` says how the drag ended,
      so every drag out needs a session object of its own. A callback on `KortexPlatform` beside `startDrag` is
      the cheaper shape, but it tells the host rather than the content that asked. **Settled by the grab-serial
      entry below**, and by neither of those: `startDrag` answers from the call, so `onTransferCompleted(null)`
      is called on a refusal without keeping anything, and `TooLarge` left the list because a drag of an image no
      transfer can carry now starts and delivers nothing. The three reasons still stop at the host; content hears
      only that the gesture did not complete, as it does on Compose's own desktop.

- [x] **A drag's own failure does not stop the content that failed.** There are no longer two routes to be out of
      step. The drag path runs no content off the loop at all now: `KortexSurface.startDrag` marshals where it is
      called, and the one piece of content's own code it runs, the `onTransferCompleted` Compose calls when a drag
      does not start, runs inside the pointer event that asked for the drag. So `runContent` catches a throw out
      of it and `record` sets the gate, as for anything else content throws. `SurfaceScene.contentFailed` is gone
      as a door into the crash: the composition's own `onFailure` is the only way to `firstCrash` now, so a
      surface cannot end as crashed while the scene behind it still runs content, and no second route can be
      added without noticing. The teardown was never the problem and is unchanged.
      (`KortexSceneTest`, which goes red when that channel is called from a thread of its own instead)

- [x] **A drag out of kortex is a copy and nothing else.** A drag out now declares whatever content listed, and
      the action it settled on reaches the content that dragged, which is what a move needs: the source is the
      side that removes what left it, and nothing else tells it to. `KortexPlatform.startDrag` takes a
      `KortexDrag` carrying the payload, the actions and an `onEnded`; Compose's own `DragAndDropTransferData`
      already had all three, so content writes no kortex-specific code for it. A drag offered only as a
      `Link` never starts: Wayland has no such action, and carrying it as a copy would do what content said it
      would not allow.
      **A drag arriving here is still only ever a copy, and the reason is the compositor.** Read off
      `DragWireTest`'s own trace on Hyprland 0.56.2: it answers a drag's enter with `wl_data_offer.action(2)`,
      a move, before this side has said anything, and sends nothing further after four
      `set_actions(3, 1)` naming both and preferring copy. So a destination that names move gets move, whatever
      the user held, and a picture dragged out of a file manager would be deleted from it on a plain drag.
      `TAKEABLE` is copy alone until a compositor is shown to honour the preference, which is one line to flip.
      **Hyprland also sends no `wl_data_source.action` at all**, so the source cannot learn what a drop settled
      on. `dnd_finished` says a drop happened, so content is told the action it offered rather than null, which
      would say its gesture never completed. Copy wherever content offered it: reporting a move that was not one
      has content delete what nobody took, and the opposite mistake leaves the same thing in two places.
      `ask`, the third action, is an entry of its own below.
      **Both compositor findings are confirmed against a third-party source**, not only against kortex talking
      to itself: a file dragged out of Dolphin 26.08.1 by hand arrives with `source_actions(3)` and
      `action(2)`, a move settled before kortex has answered anything. `LiveDropProbe` is how that was read.
      (`KortexSceneTest`, `DragAndDropTest`, `DragWireTest`)
- [x] **A drag out of a file manager lands on kortex, which reads `text/uri-list` now.** `UriListMime` is a
      third `Mime` family beside `TextMime` and `ImageMime`, ahead of both in `Mime.all`, because an
      application offering a file list and a text sends the same files under each and only the list says that
      they are files. No `Clip` offers it, so nothing this client copies is advertised as a list of files:
      putting `text/uri-list` into `TextMime` instead, which is the cheap version of this fix, would have had
      every plain-text copy claim to carry files. `decodeUriList` reads RFC 2483's framing and nothing else,
      so a URI reaches content percent-encoded and under whatever scheme it arrived with, since only the
      application that sent it knows what anything other than a `file` points at. `KortexDragOffer.readUris()`
      hands them over, `ClipboardError` gained `NoUris`, and a drop opens a third transfer drained on a task
      of its own beside the text and the image, with the two bounded joins folded into one `joinDrain`.
      **Confirmed live against the source that found the defect**: `/tmp/test-file.txt` dragged out of
      Dolphin 26.08.1 by hand arrives as `offer("text/uri-list")`, is answered
      `accept(serial, "text/uri-list")` where it used to be `accept(serial, nil)`, opens exactly one
      `receive("text/uri-list", fd)` and no other, and reaches content as `Ok([file:///tmp/test-file.txt])`.
      The file is untouched afterwards.
      What a drag can carry a paste still cannot, which is an entry of its own below.
      (`UriListTest`, `DragAndDropTest`, `LiveDropProbe`)
- [x] **A sandboxed source's files cannot be read, and no file copy can be made.** kortex reads a file list
      off the clipboard and off a drag, and drags one out: `KortexDragSource.Files` offers files as the
      `text/uri-list` a file manager reads, `Clip.Uris` writes RFC 2483 with a CRLF after every line
      including the last, and that last terminator is what makes `encodeUriList` and `decodeUriList` each
      other's inverse.
      **The drag half of this entry is closed, cover and all.** `DragWireTest` drags files from kortex to
      kortex and reads the path off the wire: the source offers a uri list and nothing else, the destination
      opens the transfer for it, which is `Drag.uriListType` picking the type, and the uris reach content as
      themselves. None of that had automated cover before, because no test could make a file manager drag on
      demand.
      **And confirmed out of kortex into Dolphin 26.08.1 by hand**, which is the only way to know that what
      kortex writes reads as *files* to a destination not written alongside it rather than merely as bytes.
      The wire is one `offer("text/uri-list")` and no other, `set_actions(1)`, one `send("text/uri-list")`
      that Dolphin asked for, and `dnd_finished`. Both files appeared, `a file.txt` among them with its space
      decoded out of the `%20`, and both originals were still in `/tmp` afterwards: a copy, not a move.
      Content was told `Copy`, and it came from `assumedAction` rather than from the compositor, since
      Hyprland sends a drag source no `wl_data_source.action` at all.
      **The key a sandboxed source offers is read now**, and handed to content through
      `KortexDragOffer.readPortalKey`. `PortalMime` sits after `UriListMime` in `Mime.all`, so an application
      offering both gets its file list preferred: that is the one kortex can hand over without the reader
      doing anything, where a key means nothing until someone calls D-Bus. The docs name that call rather
      than leaving a caller to find it, read off the running portal rather than remembered:
      `org.freedesktop.portal.FileTransfer.RetrieveFiles(key, {})` on `org.freedesktop.portal.Documents` at
      `/org/freedesktop/portal/documents`, whose signature there is `sa{sv}` answering `as`.
      kortex does not make the call and should not: that is D-Bus, and `:wayland` speaks Wayland. The entry
      below holds that seam.
      Cover is desktop-free, but the reason given for that was wrong. **Dolphin 26.08.1 offers
      `application/vnd.portal.filetransfer` on an ordinary drag**, unsandboxed, so a sandboxed application
      is not what it takes to see a key at all. Confirmed by hand with `LiveDropProbe`: every drag out of
      Dolphin offers exactly `[text/uri-list, application/vnd.portal.filetransfer]`, kortex opens a transfer
      for both, and the key arrives decoded, `_WtCq8syHMFcs8bgYF6JGA` on the run that was watched. So the
      drain has been running against a real source all along and the probe simply never printed what came
      out of it. It does now.
      **The step after is taken too, and the answer is that a key lives exactly as long as its drag.**
      `LivePortalProbe` asks `RetrieveFiles` with one key on both sides of the drag ending:
      `during-the-drag=[/home/fromml/Downloads/test.txt]`, a real path, and
      `after-the-drag=AccessDenied, "Invalid transfer"`. The message is what settles it, since the two
      guesses made different predictions: the transfer is withdrawn with the drag, and it is not the portal
      scoping retrieval to a particular caller. `busctl` was refused earlier for the first reason and not
      the second.
      So a caller must resolve a key before it returns from the drop, and `readPortalKey` says so now.
      The probe carries `:wayland` and `:dbus` on one classpath, which a test may and the toolkit may not:
      that dependency is on `jvmTest` alone and carries a comment saying nothing under `commonMain` may
      reach for it.
      **The clipboard side is done too, both halves.** `KortexClipboard.setUris` puts a `text/uri-list` on
      the clipboard and offers that type alone, so pasting into a text field gets nothing: a clipboard holds
      one selection, so copying the same paths as text is `setText`'s to do and which of the two a copy
      means belongs to the caller. `readUris` answers this client's own copy from memory now, the way
      `readText` and `readImage` already did.
      **A URI carrying a CR or LF is refused, as `ClipboardError.UriHasLineBreak` naming it.** RFC 2483
      separates URIs by CRLF and a URI may carry neither unencoded, so one that did would reach the reader
      as two entries and `encodeUriList` and `decodeUriList` would stop being each other's inverse, silently.
      Refused rather than escaped, because a read does not decode percent-encoding either and a write that
      encoded would be the asymmetry. The same corruption applied to a drag and had not been noticed:
      `asClip` answers a `Result` now and `startDrag` refuses one too.
      **The read half had cover after all**, and the reasoning that said otherwise was right about the
      mechanism and wrong about the conclusion: `receiveSelection` does answer `NoSelection` before it picks
      a type, and getting past that does need keyboard focus, which is exactly what `ClipboardFocusTest`
      has. It is the one class allowed to use `wl-copy`, so `wl-copy --type text/uri-list` drives
      `receiveUris`'s pick for the first time.
      Verified against a third party rather than against itself: `wl-paste` must print the exact bytes
      including the list's last CRLF, since a reader given one line without it would see the same files and
      only the bytes say whether the framing survived. That assertion caught `wl-paste` appending a newline
      of its own, which `--no-newline` suppresses by appending nothing rather than by stripping anything.
      **Confirmed live against Dolphin 26.08.1, both directions, by hand.** A copy out of kortex pastes as
      two real files, `kortex two.txt` among them with its space decoded back out of the `%20` that
      `Path.toUri` wrote, so `text/uri-list` on its own is enough and KDE's file manager wants no companion
      type beside it. That is the question `wl-paste` could not answer, since wl-clipboard hands over
      whatever bytes it is given. A copy made *in* Dolphin reads back the other way as
      `[file:///home/fromml/Downloads/test.txt]`, which is a list framed by a file manager rather than by
      `wl-copy`. `LiveClipboardProbe` is the probe that did it.
      Worth knowing for anyone reaching for `readUris`: reading another application's copy needs one of your
      surfaces to hold keyboard focus, and a layer surface asking for none never gets it. The probe asked
      for no keyboard at first and read `NoSelection` every time, which is the documented answer and not a
      defect.

      **What running this costs: the suite empties the clipboard.** `ClipboardFocusTest` ends with
      `wl-copy --clear`, which clears rather than restores, so whatever was copied before a run is gone
      afterwards. That was already true of the text tests and is worth knowing before running the suite.
- [x] **D-Bus had no home, and the modules kortex wants next need one.** `:tray` is the heaviest:
      StatusNotifierItem and DBusMenu are both D-Bus, and it works a client far harder than anything else
      here. `:mpris` is D-Bus as well, and notifications, UPower, logind, NetworkManager and BlueZ would be
      if they are ever wanted. `:hyprland` is not: its own IPC is a newline protocol on a unix socket, which
      shares nothing with D-Bus but the word socket.
      So D-Bus is infrastructure rather than a detail of whichever piece needs it first, and its smallest
      consumer must not settle its shape: the portal is one method call and `:tray` is a protocol.
      Decided: a module of its own owns the client, whatever needs it depends on that module, and
      **`:wayland` does not**, which is what keeps "no helper binary, no socket" true of the toolkit. The
      portal already falls along that seam, since a transfer key arrives on the Wayland wire and means
      nothing until a D-Bus call turns it into paths.
      The module list, and the contract every provider keeps to, are under "Providers"; so is the choice of
      client, which belongs to `:tray`.
- [x] **A drop no longer tells content the drag was a move kortex never offered.** Hyprland 0.56.2 sends
      `wl_data_offer.action(2)` before the destination has answered anything and never sends another, so
      `DataOffer.settledAction` stayed `Move` through all 91 `set_actions(1, 1)` of a drag out of Dolphin
      26.08.1, each naming copy and preferring copy. That cost nothing while no drag out of a file manager
      could land, and stopped being free the moment one could, because a move is the destination's cue to
      delete what it took the files from.
      `asDragAction` takes the set the destination offered now, and an action outside it reads as a copy, the
      same as nothing settled at all. `wl_data_offer.set_actions` settles on what both sides offer, so an
      action outside that set is one the compositor owed an update on, and the two mistakes it leaves open
      are not symmetric: content told a move it never allowed deletes what nobody moved, where the other way
      round leaves one thing in two places. That is the call the source half of the same drag already made,
      recorded two entries above, so both halves answer the question the same way now.
      Covered live rather than only as a mapping: Hyprland settles the same premature move in a drag from
      kortex to kortex, so `DragWireTest` asserts both that the wire carried `action(2)` and that content was
      told a copy. Without the first of those, a run where the compositor settled a copy of its own accord
      would pass and say nothing at all. (`DragAndDropTest`, `DragWireTest`)




- [x] **A layer surface can wait for its first configure forever.** `LayerShellSurface.waitForConfigure` spins on
      a blocking `display.dispatch()`, so a compositor that never answers leaves the call there with nothing to
      end it. `XdgToplevelSurface` and `XdgPopupSurface` take a sliced dispatch against a four second budget
      instead, and break out when the dispatch reports a dead connection, which is what makes a test mutation
      that removes the configure fail rather than hang. The layer role predates that and was left alone while
      spec C added the other two. It has that bounded wait now: `waitForConfigure` calls the shared
      `awaitConfigure`, the same one the other two roles take.

- [x] **No test carries a drag across the wire, and one attempt got most of the way.** `DragAndDropTest` drives
      the scene directly and never lets a compositor introduce an offer, so `wl_data_device`'s own side has no
      cover. An attempt placed two layer surfaces through a real shell, one a `dragAndDropSource` and one a
      `dragAndDropTarget`, and drove a press, a slop-clearing move, eight motions across the screen and a release
      with a `zwlr_virtual_pointer_v1`. What it established: Compose's gesture fires, `KortexSurface.startDrag`
      encodes off the loop, and `WaylandClipboard.startDrag` answers `Ok`, so **the request reaches the
      compositor with a real serial**. What it did not: the destination surface saw no `onStarted`, `onEntered`
      or `onDrop`, and Hyprland logged nothing about the drag. **Both open questions here are now answered, and
      neither answer is the one this entry guessed.** The virtual-pointer hypothesis is dead: a
      `zwlr_virtual_pointer_v1` forwards into the ordinary `IPointer` event set (`VirtualPointer.cpp:22-29`),
      lands in the same `setupMouse(SP<IPointer>)` a physical mouse uses (`InputManager.cpp:94-97`), and
      `attachPointer` is generic over `IPointer` (`PointerManager.cpp:963-1000`), so the drag-focus path cannot
      tell the two apart. And Hyprland was not silent by choice -- `initiateDrag` logs at DEBUG
      (`DataDevice.cpp:574`), which is the default threshold (`Logger.cpp:10-12`), so either the log was read
      where that line could not appear or `start_drag` never reached `initiateDrag`. The real cause is
      `decline` destroying the live offer; see the first entry under "Audit against the reference
      implementations". `DragWireTest` carries one across the wire now and reads the whole session off it,
      so the attempt kept at `.superpowers/sdd/run/LiveDragTest.kt.attempt` is superseded.
- [x] **A bare test surface silently swallowed a drag out.** `bareSurface` built its `KortexSurface` without an
      `onStartDrag`, and the default was `{ _, _ -> Ok(Unit) }`: a no-op that *reports success*. Content that
      asked to drag out of such a surface was told its drag had started while nothing was ever sent, so neither
      side failed and any drag test written on that harness passed on the silence. The refusal is now one
      function, `refuseDrag` in `KortexSurface.kt`, answering `ClipboardError.NoClipboard`, which is what a
      surface with no clipboard behind it honestly is; all four factories and both `bareSurface` and
      `onBareSurface` default to that one, since a second copy of the rule would drift unobserved. Both
      harnesses also take the parameter now, so a test that wants a working drag can hand one over.
      `DragAndDropTest` pins it. Nothing relied on the old default: `DragWireProbe` and `LiveDragProbe` both go
      through `createApplication`, which passes `clipboard::startDrag`, and `:compose` uses its own fake.
      Proved by mutation, twice: the first attempt put the refusal in both the factory and the harness, and
      mutating the factory's copy changed nothing, which is exactly the drift the single definition removes.

## Housekeeping

- [ ] **Three module build files share 21 of their 30 lines, and the probe task is now copied three times.**
      `dbus`, `tray` and `notification` differ only in their dependencies; `compose` and `wayland` share the
      same opening. The `probe` task, a `JavaExec` over a test compilation's classpath, is pasted into
      `wayland`, `tray` and `notification` verbatim. When the second copy went in the note was that a third
      would justify extracting it, and there is now a third.
      Open: a convention plugin in a `build-logic` included build, which is what Gradle's own guidance asks
      for, carrying the toolchain, `explicitApi`, the `jvm()` target and the probe task. Each module would
      keep only its dependencies. Noted rather than done because a new included build is a design change.

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
      **Run on this machine at last, and all seven pass: 397 of 397 in `:wayland`.** Nothing crashed, with
      ags, ghostty and dunst open and Steam and Discord closed, and no headless output survived the run.
      That is the second clean run on GTK 4.22.5 and it still does not prove a one-in-256 crash gone, so
      the tag stays: Steam's own crash was a different bug, X11 GTK2 on a RandR output that no longer
      exists, with no fix recorded and no reason to think there is one.
      **The run found a test that could never have passed.** `MultiSurfaceTest.awaitPanelCount` slept and
      polled `hyprctl` without ever pumping the shell, so the panel for a new output could not be created
      while it waited: kortex only makes one when it has handled the global announcing the output. Its two
      siblings in the same file pump, and so does the removal half of the very same test. Fixed to pump.
      That is what gating costs, and it is worth writing down: a test nobody runs is a test nobody notices
      rotting, and this one was wrong from the day it was written.
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
- [x] **`SurfaceConfig.contextMenu` and `MenuAnchor` are gone.** `ContextMenu` opens an `xdg_popup`, whose
      positioner the compositor solves, so the layer-shell corner flip had no caller in `:wayland`, `:compose`
      or `:bar` and could not be given one without undoing that. Deleted with its enum, `MenuAnchorTest`'s
      eight cases and the one in `SurfacePresetTest` that took the desktop to place a menu nothing ships,
      along with the seven constants and two imports that went with them. `OutputGeometry.logicalWidth` and
      `logicalHeight` stay: they are public API a host reads for its own layout, and the flip was only one
      reader of them. `awaitReserved` stays too, since the panel and dock cases still use it.

## Providers

Modules that carry a desktop's own state to whatever draws it. A provider gives the data and no UI, because a
widget is where a toolkit starts having opinions about what a bar should look like, and kortex has none. The
caller builds its own.

**`:dbus`, `:tray` and `:notification` now exist, and building them corrected this in four places**, which
is what the draft note said would happen. Every rule survived; what changed is underneath them.

- **There is no FFI.** D-Bus is a wire protocol on a unix socket, not a library, so the sd_bus against
  libdbus-1 question below is moot and `:dbus` is pure Kotlin on `java.net.UnixDomainSocketAddress`. The
  reasoning that made sd_bus look cheap, that it is far less code, holds from C and not through FFM: all of
  that terseness is in variadic format strings, and only the non-variadic subset is reachable, which is the
  same shape and size as libdbus-1's iterators. It also makes the "a socket can be faked" claim below true,
  since it is only true while kortex owns the socket.
- **`SCM_RIGHTS` is unreachable**, because `SocketChannel` exposes no ancillary data. A provider that needs a
  file descriptor cannot be written on this client, and kortex must never negotiate `UNIX_FD`. Nothing in
  `:tray`, `:mpris` or `:notification` passes one.
- **A provider sees signals it never subscribed to.** `NameAcquired` and `NameLost` are addressed to the
  connection and arrive with no match rule at all, found by a test that assumed the opposite. A provider must
  filter rather than trust that everything arriving is its own. `:notification` gets told it has stopped
  being the server for free.
- **A fake bus was written and thrown away.** It reimplemented the protocol, so it agreed with whatever
  kortex had got wrong about it, which is the one thing a test here has to catch. Every test talks to the
  session bus that is running, as every `:wayland` test talks to a live compositor.

What was settled stayed settled: data and no UI, one flow carrying a `Result`, typed errors on `IError`,
nothing running while nobody is watching, and no Compose or `:wayland` anywhere. The server side is built
too, and the one rule `:notification` genuinely departs from is written up in its own entry below. What is
still draft is `:mpris` and `:hyprland`, neither of which exists, and the system bus every one of
UPower, logind, NetworkManager, BlueZ and systemd lives on.

What every one of them keeps to:

- **One `StateFlow` per thing, carrying a `Result`.** `Tray.items` is a
  `StateFlow<Result<List<Tray.Item>, TrayError>>`. The error says why there is no data, the way
  `ClipboardError.NoSelection` already does, so nothing needs a second flow to say whether the first can be
  trusted and no two flows can disagree.
- **A `StateFlow` always holds a value, so the first one has to be honest.** `Err(NotConnected)` and not
  `Ok(emptyList())`, which reads the same as a tray nobody has put anything in.
- **Typed errors rooted in kern-result's `IError`**, as `KortexError` and `ClipboardError` are. No strings,
  and no bare `IError` where a caller would have to cast to learn anything.
- **An instance, not an object.** A connection has a lifetime and can fail, and a global one can be neither
  faked nor scoped, which throws away the thing providers are for.
- **Nothing runs while nobody is watching**, through `stateIn(scope, SharingStarted.WhileSubscribed(), ...)`.
  An unwatched provider should hold no bus match rule, for the same reason an idle bar draws no frames.
- **Commands answer `EmptyResult`, and what they changed comes back through the flow.** A provider that can
  only be read is the exception, not the rule: a notification is dismissed, a tray item is activated, a
  player is paused. Those are `suspend fun`s returning `EmptyResult<E>`, the shape `KortexClipboard.setText`
  already has, and none of them returns the new state, which arrives where all state arrives.
- **No Compose, and no dependency on `:wayland`.** A provider needs neither a compositor nor a composition:
  `collectAsState()` is already the bridge and kortex ships no helper for it. Tray icons arrive as a width, a
  height and ARGB bytes and stay that way, so `:tray` never reaches for `ImageBitmap`.

The point of the whole shape is that a provider is testable with no desktop at all. Every hard test in this
repository is hard because `:wayland` talks to a compositor. A provider talks to a socket, and a socket can
be faked.

| module | carries | speaks |
| --- | --- | --- |
| `:dbus` | the client itself, public so anyone can write a provider of their own | D-Bus |
| `:tray` | `StatusNotifierItem` and `DBusMenu` | `:dbus` |
| `:mpris` | what is playing, and the transport | `:dbus` |
| `:notification` | what applications have posted, and dismissing or acting on one | `:dbus`, as the server |
| `:hyprland` | workspaces, the active window | a unix socket, newline protocol, not D-Bus |
| `:watch` | a file's text, again when it changes, which is where cpu and memory live | the filesystem |

`:hyprland` is the one to keep straight: its IPC shares nothing with D-Bus but the word socket. Nothing in
this table depends on `:wayland` or `:compose`, and `:hyprland` and `:watch` depend on nothing of kortex's
at all.

**`:sysinfo` is dropped, and `:watch` replaces it.** A module for cpu, memory, temperature and battery would
have been four parsers wrapped around one mechanism: read a file under `/proc` or `/sys` and notice when it
changed. The mechanism is the reusable part, and the parsing belongs to whoever knows what the numbers mean,
so `:watch` hands over a file's text and a caller's own `.map` makes a `MemInfo` of it. Battery is the one
that loses something, since UPower on the system bus says more than `/sys/class/power_supply` does, and that
is a provider for when the system bus exists rather than a reason to keep a module.

- [x] **`:dbus` and `:tray` are built together, and `:tray` is what proves the client.** `:dbus` is public,
      so its surface has to serve someone writing a provider of their own: a connection, method calls, signal
      subscriptions, typed errors and a lifetime. Designing that in the abstract would get it wrong, so it is
      built against `:tray` and published once `:tray` works.
      `:tray` is the hardest consumer there is. `StatusNotifierItem` is one interface and `DBusMenu` another,
      it needs signals as much as calls, and icons arrive as `a(iiay)`, a width, a height and ARGB bytes. A
      client that survives both survives `:mpris`, which is why the rest should follow it easily.
      **`:tray` proves only half of it.** A tray host calls, subscribes and registers itself; it never
      answers. `:notification` does: it takes a bus name exclusively, exports an object, dispatches method
      calls made to it and returns their values. A `:dbus` designed against `:tray` alone would have no
      server side at all, so the two together are what the client has to survive.
      **Done: the client, the module, and all of `:tray`'s reading side.** 106 tests, against the running
      session bus and a live tray. `:dbus` carries the codec, the four message shapes, the connection with
      one coroutine owning the socket, match rules and name ownership. `:tray` reads items, decodes them and
      passes on activation, secondary activation, context menu and scroll. Two probes print the live tray
      and the live menus and watch them change: `./gradlew :tray:probe -Pprobe=<main>` with
      `com.fromwau.kortex.tray.LiveTrayProbeKt` or `LiveMenuProbeKt`.
      **`NotConnected` needed a second variant, and it is `NoWatcher`.** One passes on its own and a bar
      should draw nothing; the other will not pass and means the session has no tray at all. Both on the one
      flow, which is the rule the question was really testing.
      **Three things the specification gets wrong about reality**, each found by running against the live
      bus and each already in the code: `org.freedesktop.StatusNotifierItem` does not exist on this desktop
      and kdeconnect rejects the interface outright, so KDE's names are tried first; the watcher hands back
      one string holding a connection and a path and watchers disagree about which parts it holds; and
      `NameOwnerChanged` has to be watched beside the watcher's own signal, because an application that
      dies may take its item with it unannounced.
      **Done: `DBusMenu` as well.** `Tray.menu(item)` hands back a `Menu` for an item that exports one, its
      own provider rather than a field on the item, because an application may build its menu only when
      asked. `GetLayout` answers `u(ia{sv}av)`, the one recursive type either module meets, and the capture
      pinning it came off the socket by a separate client with its expected values read by a separate
      parser again.
      Two things the reference implementation settled that the interface XML does not say: an entry's
      `icon-data` is an encoded image file rather than the raw ARGB a tray icon carries, and it arrives
      either as bytes or base64 into a string; and `enabled` and `visible` default to true when absent,
      which is the opposite of how a missing flag usually reads.
      **Confirmed live against Steam and a connected kdeconnect**, by hand with the two probes. Steam
      registers as `:1.244/org/ayatana/NotificationItem/steam`, which is the ayatana path case
      `ItemAddress.parse` handles and that the unit test had to invent a name for; it parses. Steam also
      sends an `IconThemePath` of its own, `~/.local/share/Steam/public`, where kdeconnect sends none, so a
      caller that ignores it cannot find Steam's icon at all.
      kdeconnect's menu with two devices paired exercises what one entry could not: submenus nested three
      deep, so the recursive `(ia{sv}av)` parse is proven past the one level seen before; separators;
      entries that are `disabled`, `hidden`, and both at once; and an entry that is hidden and still
      delivers its submenu's children. Steam's sixteen entries add three more separators and nothing else.
      **Discord closed most of what those two could not.** Its item sends a real `a(iiay)` pixmap, 24 by 24
      and 2304 bytes, which is exactly `width * height * 4`, so `TrayLiveTest`'s byte-count assertion had
      real pixels to check for the first time and passes. It sends an empty `Title` with a non-empty `Id`,
      which is why that test accepts either rather than both. Its menu carries `toggle-type` `checkmark`
      with `toggle-state` 0 on Mute and Deafen, a `shortcut` of `[["Control", "q"]]` on Quit, and an entry
      with `icon-data`.
      **That `icon-data` arrived as a raw `ay` of 905 bytes beginning `89 50 4E 47 0D 0A 1A 0A`**, the PNG
      signature, which settles on the wire what was only read in libdbusmenu's docs: the bytes are an
      encoded image file and not the raw ARGB a tray icon carries. The base64-into-a-string shape is the one
      libdbusmenu-gtk's own helper writes, so a GTK application using it would produce the other branch;
      that branch is unit-tested and still unseen live.
      What no live item has sent yet is its own entry below, since it is a wait rather than work.
      **Done: the server side too.** An object is exported at a path, a call to it reaches a handler, and
      what the handler returns becomes a reply or an error reply under a name it chose. Answered off the
      pump, because a handler that takes its time would otherwise stop the socket being read; replies are
      matched by serial, so answering out of order is what the bus expects anyway. `Peer` is answered for
      every path without reaching a handler, and `Introspect` from the xml an object was exported with.
      Two existing tests failed on this and both were right to: they relied on kortex not answering calls
      addressed to itself, and an unexported path now says `UnknownObject` where it used to say nothing.
- [x] **`:notification` makes kortex the notification server, not a client.** `org.freedesktop.Notifications`
      at `/org/freedesktop/Notifications` is what `notify-send` calls, and a shell that shows notifications is
      what answers it. Read off the running bus rather than remembered: `Notify(susssasa{sv}i)` answering a
      `u`, with `CloseNotification(u)`, `GetCapabilities()` and `GetServerInformation()` beside it, and
      `NotificationClosed(uu)` and `ActionInvoked(us)` going back out.
      **Only one server can hold the name**, and on this desktop dunst holds it, so failing to take it over is
      a first-class error rather than an edge and testing any of this means stopping whatever holds the name
      first.
      Decided: it fails at once and does not queue. `RequestName` goes out with `DO_NOT_QUEUE`, and the reply
      that says the name already exists becomes a typed error of its own, so a caller is told it could not
      become the server. Queueing would leave a bar that has started, looks well, and shows no notification
      until a daemon nobody is watching happens to exit, which is the worse of the two to diagnose.
      **Decided: it carries who holds the name.** `GetNameOwner`, then `GetConnectionUnixProcessID`, then
      `/proc/<pid>/comm`, which is what turns `:1.99` into `dunst`. Two round trips and a file read on a
      path that has already failed, for the difference between a diagnosable message and a riddle.
      Everything `notify-send` can express has to survive the trip: the summary, the body, an app icon,
      actions in pairs, an expiry, and the hints that carry urgency, category, desktop entry, transience and
      inline image data. Image data arrives as raw pixels the way tray icons do, and stays bytes rather than
      becoming an `ImageBitmap`.
      `GetCapabilities` is the caller's to declare and not kortex's to guess: whether body markup, action
      icons or inline images are supported depends on what the content drawing them can render, and only the
      caller knows that.
      **Done.** 29 tests, run with dunst stopped, and one of them posts through `notify-send` itself, which
      is the only thing that proves libnotify agrees with how kortex reads a `Notify`.
      **`notifications` carries no `Result`, which is this module's one departure from the rules above.**
      The error the others carry answers "why is there no data", and here that cannot be a failure: holding
      a server means `start` already proved the name was taken and the object exported, so an empty list can
      only mean nothing was posted. The failure that matters is becoming the server at all, and that is what
      `start` returns.
      **Nothing here draws or times anything.** A notification carries the `Expiry` its application asked
      for and stays until a caller closes it, because a caller that animates one away knows when it is gone
      and kortex does not. That also made the expiry a type rather than the wire's int, where -1 and 0 are
      two sentinels hiding among ordinary millisecond counts.
      **Found on the wire, and no document says it:** `notify-send` 0.8.8 leaves the `app_icon` argument
      empty and puts `--icon` in the `image-path` hint instead. A caller drawing `app_icon` alone shows no
      icon for most of what is sent.
- [x] **`:watch` reads a file and reads it again when it changes, and the research is why there are two of
      them.** Two overloads, one name: `fileWatcher(path)` waits for the operating system, and
      `fileWatcher(path, every = 1.seconds)` reads on a tick. Both hand over `Result<String, WatchError>`,
      so moving a widget from one to the other changes nothing but the call, and both emit the file as it
      stands first and then only what changed, which is why no caller needs a `distinctUntilChanged()`.
      **Pushing is impossible for most of what a bar reads, and the kernel says so.** `inotify(7)`: "Various
      pseudo-filesystems such as /proc, /sys, and /dev/pts are not monitorable with inotify." Three probes
      on this machine agree and show how it fails: a watch on `/proc` and on `/sys/class/net/wlan0/statistics`
      is **accepted**, reports **nothing** while the contents change, and `size` and `mtime` both stand
      still, `0` for meminfo and a flat `4096` for an rx_bytes of twelve characters. So the three cheap ways
      to notice a change all fail silently, and the only thing left is reading the contents and comparing
      them.
      What push does exist, and did not help: `/proc/mounts` and `/proc/self/mountinfo` are pollable for
      mount changes, and `/proc/pressure/{cpu,memory,io}` take a written threshold and then poll, which is a
      real interface for memory pressure but not for "meminfo changed". sysfs attributes answer `poll` with
      `POLLPRI` only where their driver calls `sysfs_notify`, and **userspace cannot test whether an
      attribute does**, so an API built on it would wait forever on the ones that do not. For battery and
      network the push source is not the file at all: it is udev or the system bus, which is `:dbus`'s to
      carry when it grows one.
      **So the push overload refuses rather than going quiet.** `Files.getFileStore(folder).type()` is the
      kernel's own answer, not a guess at the path's spelling: `proc`, `sysfs`, `btrfs`, `tmpfs`. A watch
      asked for on procfs or sysfs answers `WatchError.Unwatchable(path, Pseudofilesystem.Proc)` as its
      first and only value and names the overload that works, instead of being a widget that looks fine and
      never updates.
      **The error set was wrong once, and the correction is the rule.** It first had a
      `WatchFailed(path, reason: String)`, which lumped a missing directory, an unreadable one, a watch that
      had stopped and a system limit into one case with English as the discriminator, and an `Unwatchable`
      that carried its filesystem as a string. Now every discriminator is a type: `FolderUnreadable` hands
      back kern's own `FileError` about the directory, `NoFolderAbove`, `WatchEnded` and `Unwatchable` are
      their own cases, and the filesystem is a `Pseudofilesystem` enum holding the three `inotify(7)` names.
      One string survives, on `WatchRefused`, and only because what else a kernel may refuse a watch for is
      not a set anything can close; java.nio throws a bare `IOException` for the watch limit, so matching on
      its message would be worse than saying so.
      Fixing it also removed a second set. The platform mechanism had its own `ChangeError` mirroring the
      public one case for case, which is one rule in two homes and would have drifted the first time either
      gained a case. There is one set now, split by a sub-interface: `WatchError.Stopped` covers the five
      cases a watch rather than a read answers for, which is exactly the set a platform can produce, so the
      mechanism's signal type is `Flow<Result<Unit, WatchError.Stopped>>` and no mapping sits between them.
      The split earns its place twice over, because it is also the distinction a collector acts on: after a
      `Stopped` the flow completes and nothing more will ever arrive, while an `Unreadable` leaves the
      watcher watching, so a file that is missing now may still be written later.
      Classifying it is done by asking kern rather than by reading the exception: on a failed registration
      the directory is listed, and kern's `NotFound`, `Inaccessible` or `NotADirectory` is the answer. A
      listing that succeeds is the interesting case, because it means the directory is fine and the refusal
      was about watches, which is exactly what no exception type distinguishes.
      **Decided: a cold `Flow`, not a `StateFlow`, which is the one place this departs from the provider
      rules above.** The argument is the caller's own code: `.map { MemInfo(...) }` returns a plain flow
      whatever the source was, so the derived flow needs `stateIn` either way, and a `StateFlow` here would
      have bought nothing while costing a `CoroutineScope` on every call. Cold also means the reading cannot
      outlive its collectors.
      **Decided: the directory is watched, not the file.** An editor saves by writing a temporary file and
      renaming it over the original, which never touches the original's inode, and a file that does not
      exist yet has no inode at all. Both arrive as events on the directory. Dropping `ENTRY_CREATE` from
      the registration fails exactly the four tests that depend on a rename landing, which is how that is
      known rather than assumed.
      **Built for the move to kern, since that is where it is going.** It uses kern's own `Path`, `readText`
      and `FileError` from day one, so the promotion is a move rather than a rewrite, and everything except
      the platform mechanism lives in `commonMain` with tests in `commonTest`. Two `expect`s carry the rest:
      `changes(path)`, whose signals are a `Result` because a watch can also fail halfway and throwing is
      not how this codebase reports that, and `blockingReads`, because
      `Dispatchers.IO` is not in coroutines' common API and resolves in common code only while a module has
      a single target. That second one compiled fine before it was wrong, which is the trap worth
      remembering.
      One nicety that fell out of typing the signals: an inotify queue overflow is not an error. It means
      events were dropped, so the only honest signal is `Ok(Unit)`, a re-read, which the deduplication then
      drops again if nothing actually changed.
      **15 tests, and the first kortex module whose suite needs no desktop, no compositor and no bus**: a
      temp directory for arrival, change, deletion, return, an in-place write and a save-over, two live
      `/proc/meminfo` cases for the refusal and for the interval read that sees through frozen metadata, and
      the limits, and one each for a missing directory, a filesystem root and a watch whose directory is
      deleted under it. Three mutations were run against them: removing either `distinctUntilChanged` loses the
      identical-save test, removing the filesystem check loses the refusal test, and dropping `ENTRY_CREATE`
      loses the four rename tests.
      **It belongs in kern, once a second project wants it.** kern's own rule is that something enters it
      when two real projects already need it and never because one might, and kortex is one. It lives here
      until there are two, which also lets its public surface be wrong in private first. `kern:dirs` would
      take the code but not the dependency: `dirs` deliberately pulls in only `kotlinx-io` and `result`, and
      a flow needs coroutines, so the home is a module of its own beside it, as `logger` already is.

- [ ] **Seven public commands in `:tray` have no test, and the server side made them testable.** Every
      write in that module: `Tray.activate`, `secondaryActivate`, `contextMenu` and `scroll`, and
      `Menu.send`, `aboutToShow` and `activationRequests`. No test names any of them. The reading half is
      covered twice over, by unit tests and against three live items, and the half that acts is covered not
      at all, which is the wrong way round: a `scroll` that sends the wrong argument order or an `activate`
      that quotes the wrong path fails silently and looks like an application ignoring it.
      They were skipped because exercising them against a live item means really activating it, opening
      Discord's window or Steam's menu, which is a side effect on somebody's desktop. That reason expired
      when `:dbus` gained `export`: a test can export its own object implementing
      `org.kde.StatusNotifierItem`, register it with the real watcher through `RegisterStatusNotifierItem`,
      let `Tray` discover it, then call `activate` and assert the handler was given `Activate(x, y)`. Real
      bus, real watcher, kortex on both ends, and nothing visible happens because the item it drives is the
      test's own. `ExportedObjectTest` is the shape to copy.
      Open: that test class, and whether the same trick covers `DBusMenu`'s `Event` and `AboutToShow`.

- [ ] **`DBusConnection.kt` is 538 lines and one piece of it is a different protocol.** The rest is
      cohesive, all of it things done with a connection: calls, signals, match rules, name ownership,
      property reads, the exported side and the pump. The handshake is not. `handshake`, `writeAscii` and
      `readLine` speak SASL over text lines, before a single D-Bus message exists, and they are the only
      code in the file that reads a `\r\n`-terminated string off the socket.
      Open: whether to move those three, and nothing else, to a file of their own. Noted rather than done
      because moving responsibilities is a design change, and the gain is a smaller file rather than a
      reader who was confused.

- [ ] **Four tray paths are written and no live item has ever sent them.** Draft, and deliberately not
      work: the code exists, the unit tests cover it, and what is missing is an application that sends the
      thing. Left open so that the next time one turns up it is read rather than assumed, and so nobody
      counts the live sessions as having covered everything.
      `TrayStatus.NeedsAttention` and `TrayItem.attentionIcon` go together: an item asking to be noticed is
      meant to be drawn with its attention icon instead of its usual one, and nothing has asked. An
      application with an unread mention is the likely sender, and Discord did not do it while a probe was
      running.
      `TrayItem.overlayIcon`, which is the small badge drawn over the main icon. Discord sends a 24 by 24
      pixmap for its main icon and no overlay at all, so the two are independent and only one is seen.
      `MenuDisposition` other than `Normal`. Every live entry so far reads as normal, which is also the
      default a missing hint takes, so an `informative`, `warning` or `alert` would be the first time that
      `when` goes anywhere but its else branch.
      `MenuIcon.data` arriving as base64 inside a string rather than as an `ay`. Discord sends the `ay`, and
      the string is what libdbusmenu-gtk's own helper writes, so a GTK application using that helper is the
      sender to watch for. Both branches are unit-tested; one has now been seen and the other has not.
      None of this is a defect and none of it blocks anything. The probes to read it with already exist:
      `./gradlew :tray:probe -Pprobe=com.fromwau.kortex.tray.LiveTrayProbeKt` and the `LiveMenuProbeKt`
      beside it.
- [x] **kotlinx-coroutines is not in the version catalog, and every provider needs it.** `:wayland` uses
      `Dispatchers.IO` and `withContext` today and gets them transitively through Compose, which holds only
      while every module depends on Compose. A provider must not, so the first Compose-free module ended that
      freeride. `kotlinx-coroutines-core` is in the catalog now and `:dbus` takes it as `api`, its own surface
      being a `SharedFlow`. Nothing relies on the older one Compose supplies transitively any more.

## Where the work stands

Written down because the rest of it lives in a conversation and in a git-ignored directory, and neither
survives on its own.

- **All of it is on `master` now, nothing pushed, working tree clean.** How far ahead is git's to count
  and not this file's to restate: `git rev-list --count origin/master..HEAD`. `reactive-surfaces` and then
  `windows-and-input` went in as fast-forwards, so there are no merge commits for either and `master`'s
  tree is exactly what the branch tip held; both branches are gone. The merge decision this section used
  to carry is therefore closed.
- **No commit message carries a `Co-Authored-By: Claude` or `Claude-Session` trailer.** All 1108 of them
  were stripped from the 565 unpushed commits, bounded at `origin/master` so nothing published moved.
  The pre-rewrite tips are kept under `refs/backup/pre-trailer-strip/` and `refs/original/`, which also
  keeps the old objects alive, so `git gc` reclaims nothing until those refs go.
- **563 tests green on `master` in one run: 390 in `:wayland`, 73 in `:dbus`, 33 in `:tray`, 27 in
  `:compose`, 15 in `:watch`, 14 in `:notification` and 11 in `:bar`.**
  No failures, no errors, nothing skipped, run with `--rerun-tasks` so none of it came from the cache, and
  with the session free, which needs saying because a suite that takes focus and drives the pointer is not
  being measured while the desktop is in use.
  That is what a plain `check` runs. `NotificationServerTest`'s fifteen make 578 in all and passed in a run
  of their own earlier the same day, before `:watch` existed, which is why no single run has shown 578. It is
  `@TakesTheName` and wants `-Pkortex.notificationTests=true` and whoever holds
  `org.freedesktop.Notifications` stopped. **Stopping it is not the end of it**: the name is D-Bus
  activatable, so dunst comes back on its own as soon as kortex releases it, which invalidated a run that
  started a minute after the stop. Stop it and start the run in the one command.
  The seven `@Hotplug` tests make `:wayland` 397 with `-Pkortex.hotplugTests=true`, and the run that first
  did it on this machine is written up under its own entry above. They did not run this time: they hotplug
  the live desktop, GTK 4.22.5 fixed the crash that banned them but Steam's was a different bug with no
  fix, and Steam was open.
  **The suite empties the clipboard.** `ClipboardFocusTest` ends with `wl-copy --clear`, which clears rather
  than restores, so whatever was copied before a run is gone after it.




- **The audit's own reports are in `.superpowers/sdd/protocol-audit/`, nineteen files, and that path is
  git-ignored.** `SUMMARY.md` is the way in; each `report-*.md` quotes both sides of every finding. A
  `git clean -fdx` takes all of it. Every entry below carries its own evidence for that reason, but the
  reports hold the roughly 190 items that were checked and found correct, which nothing else records.
- **Running the suite takes the desktop.** `WindowTest`, `WindowManipulationTest`, `PopupTest`,
  `PopupTeardownWireTest`, `DragWireTest`, `MinimizeWireTest`, `PopupGrabWireTest` and
  `PointerReleaseOrderTest` take focus, re-tile open windows and drive the pointer, so they want a session
  kept free. Ask before starting a run.


- **`LayerShellSurfaceTest`'s dead-connection test failed once, in a run that was not clean, and has
  passed in every clean run since.** It kills the
  connection by binding a global no compositor advertises, then asserts `waitForConfigure` comes back as
  `KortexError.ProtocolViolation` naming `wl_registry`; that run returned `ConnectionError(errno=104)`,
  ECONNRESET, because `requireAlive` reports a `ProtocolViolation` only for EPROTO and the connection's own
  errno otherwise. The mechanism by which the two compete is worth having written down for the next
  occurrence: libwayland keeps one error per display and never replaces it, `display_fatal_error` and
  `display_protocol_error` in `wayland-client.c` both returning early while `display->last_error` is set,
  so whichever is recorded first is the one the test reads.
  **It is not established as a flake.** The desktop was being used during that run, which is enough to
  explain a timing-sensitive test on its own, and five solo runs of the class afterwards were clean.
  Nothing has been changed in it.

- **Read gradle's exit code directly, not through a pipe.** `./gradlew … | tail` returns tail's status,
  which made three "green" reports meaningless before it was noticed. The counts in
  `*/build/test-results/*/TEST-*.xml` are the evidence; a run that executes nothing also exits 0.

## Audit against the reference implementations

A fourteen-agent audit covered every line of kortex against Weston, libwayland, Hyprland 0.56.2,
mutter, KWin, wlroots, Compose Multiplatform 1.12.0 and the normative protocol XML. It returned 6
Critical, 28 Important and 51 Minor findings, and about 190 items verified correct.

**Start at `.superpowers/sdd/protocol-audit/SUMMARY.md`.** It holds the tally by area, the six
Criticals in one place, the four conclusions that outlive the individual findings, and the
recommended order of work. Beside it are the fourteen per-area reports, each quoting both sides of
every finding:

| file | covers |
|---|---|
| `report-data-device.md` | clipboard and drag and drop (Critical 1) |
| `report-core.md` | the hand-built FFM binding, marshalling, dispatch (Critical 2) |
| `report-xdg-shell.md` | window, dialog and popup lifecycle |
| `report-input.md` | pointer, keyboard, xkb, cursor |
| `report-surface.md` | surfaces, outputs, buffers (zero Critical, zero Important) |
| `report-compose.md` | the `compose` module against Compose Multiplatform 1.12.0 |
| `report-bar.md` | the `bar` module against kortex's own public API |
| `report-open-questions.md` | the 15 questions wave 1 could not answer (Critical 3) |
| `report-minors-verified.md` | adversarial re-check of the 12 Minors; two were false |
| `report-tests-input.md` | input, clipboard and DnD tests (Critical 4) |
| `report-tests-window.md` | window, output and protocol tests (Criticals 5 and 6) |
| `report-tests-surface.md` | surface lifecycle and rendering tests |
| `report-tests-harness.md` | the test harness, `:compose` and `:bar` tests |
| `report-build-docs.md` | build files, config and this file |

That directory is git-ignored scratch, so every entry below carries its own evidence and file
references and stays actionable on its own once the reports are gone.

- [x] **A drag kortex declines destroys the compositor's live offer, which cancels the drag at its source.**
      `DataDevice.onEnter` reaches `decline` (`DataDevice.kt:206-211`) by three routes, and `decline` sends
      `wl_data_offer.destroy` on the offer the compositor still holds. Weston's toytoolkit destroys a drag
      offer only in `data_device_leave` (`clients/window.c:3847-3856`), never inside `enter`. A drag's first
      `enter` always names the origin surface, whose own tree has no drop target, so `sendDragEnter` returns
      false and the drag dies before the pointer moves: this is why `start_drag` succeeds and no destination
      is ever entered. The second manifestation is live today, and is the worse one: any other application's
      drag over a kortex surface is cancelled globally whenever kortex declines it, and `text/uri-list` is
      absent from the whole module, so a file dragged from a file manager over the bar dies. Fixed as
      `window.c:3795-3856` does it: `decline` accepts nothing, sets no actions, and keeps the offer until
      `wl_data_device.leave`, and the comment that stated the bug as if it were the contract is gone.
      **Confirmed live against a source kortex did not write**, which is the only way this one can be: a file
      dragged out of Dolphin over the bar draws `accept(serial, nil)` and `set_actions(0, 0)` at the enter and
      `destroy` only at the leave, and the drag survives to be dropped elsewhere. The `text/uri-list` clause
      above is now an open entry of its own, under "Keyboard and clipboard": the refusal is correct, and what
      remains is that kortex has nothing to accept.

- [x] **Two layer-shell requests go out with no version guard, which kills the client on wlroots 0.18 and older.**
      `set_exclusive_edge` (`since="5"`, `LayerShell.kt:194-199` and `:320-324`) and `set_layer` (`since="2"`,
      `:215-220`) use plain `LibWayland.marshal`; they are the only two versioned requests in the module not
      routed through `marshalIfSince`. libwayland-server answers a request below the negotiated version with
      `wl_display.error(invalid_method)` (`wayland-server.c:451-471`). wlroots capped `LAYER_SHELL_VERSION` at
      4 through 0.18.3 and only reached 5 in 0.19.0. Hyprland advertises 5, so nothing fires here. Fixed, and
      in a wider shape than routing those two: `LibWayland.marshal` reads each request's own `since` off the
      interface table and skips one the negotiated version cannot carry, so no call site names a version by
      hand and `marshalIfSince` is gone. The three KDoc claims that made the guards look optional went with
      it.
- [x] **Destroying a window while a popup opened from it is still up disconnects the client on GNOME.**
      `KortexShell.takeDown` (`:512-521`) closes a slot's surface without ending the popups under it;
      `endPopupsUnder` (`:492-497`) already does exactly the right thing and its only caller is `rebuild`
      (`:365`). `reconcileSlots` walks `placed` parent-before-child, so the parent's `xdg_toplevel.destroy`
      reaches the wire first. mutter connects `on_parent_surface_unmapped` to the parent surface
      (`meta-wayland-xdg-shell.c:679-695`) **outside** the `if (seat)` block, so it fires for non-grabbing
      popups, which is all kortex has, and posts `not_the_topmost_popup`. Fixed: `KortexShell.takeDown` calls
      `endPopupsUnder(slot)` before it takes the surface down.
- [x] **No test reaches the `wl_data_device` destination path at all, so the drag defect had nothing to catch it.**
      Nothing in the repository constructs a `DataDevice` or calls `onEnter`, `onMotion`, `onLeave` or
      `onDrop`; `DragAndDropTest` drives `KortexScene.sendDragEnter/Move/Leave/Drop`, the Compose seam one
      layer beneath, and its `withDropTarget` fixture always accepts, which negates all three routes into
      `decline`. Replacing `decline`'s whole body with `= Unit`, deleting `finish`'s `actionSelected` guard,
      or making `onEnter` accept nothing each leave the suite green. Covered by the second of the two shapes
      this proposed: `DragWireTest` asserts on the wire that no `wl_data_offer.destroy` precedes the `leave`,
      and that one does follow it.
- [x] **`PopupTest` performs the fatal parent-destroy sequence on every run and reports green.** Four of its
      six tests leave a popup on screen at block end; only the teardown test at `:92` sets
      `showing.value = false`. `onApplication`'s `useOrFail` then calls `KortexShell.close()`, which runs
      `reconcileSlots()` and `takeDown` parent-first. The test at `:126` has a `Window` parent, the exact
      shape mutter kills. Applying the `endPopupsUnder` fix above changes no test's colour either way, which
      is the proof in reverse that nothing pins it. Covered by `PopupTeardownWireTest`, which does exactly
      that.
- [x] **No test binds a global below what kortex asks for, so a missing `since` guard cannot be observed.**
      Every version assertion in the suite passes the maximum (`ProtocolVersionTest.kt:45,63,80`,
      `ClipboardTest.kt:389`, `OutputGeometryTest.kt:38,41`, `BoundOutput.kt:14`, `SurfaceScaleTest.kt:60`).
      Replacing the one guard the layer-shell path has, `marshalIfSince` at `LayerShell.kt:247`, with a plain
      `marshal` leaves everything green. Both requests in the second entry above already have a green test
      driving them. The test that would cover the whole class is an entry of its own below.
- [x] **The `since` guard is driven against a version chosen here, not one a compositor happened to offer.**
      `LibWayland.marshal` reads each request's own `since` off the interface table and skips one the
      negotiated version cannot carry, which is what keeps the client alive on a compositor older than the
      request it was about to send.
      This entry said nothing drove it, and that was wrong. A real registry trace settled it: kortex asks for
      `wl_compositor` 7, Hyprland advertises 6 and binds at 6, and `wl_compositor.release` is `since=7`, so
      `SinceFromTableTest`'s original leg does skip a request and does prove the guard runs. It proves it by
      accident of a compositor lagging, though: the day one offers 7, that leg sends the release legally,
      passes, and covers nothing.
      Both halves are answered. A second leg binds `wl_compositor` at 1 on purpose and sends
      `wl_surface.set_buffer_scale`, which is `since=3`, so it rests on no compositor's version at all, and it
      asserts the bind took the low version before relying on it. The original leg now asserts the negotiated
      version is under `release`'s own `since` and names the version it found, so it fails loudly rather than
      quietly the day a compositor catches up.
      Taking the guard out of `marshal` kills both legs, which is what says the new one has teeth.
      (`SinceFromTableTest`)
- [x] **kortex requires `zwlr_layer_shell_v1`, and says so rather than working around it.** Decided: no
      xdg-shell-only mode. `KortexShell.createApplication` calls `requireSurfaceGlobals` before anything else
      and that list holds `zwlr_layer_shell_v1`, so a compositor without it fails with
      `Err(MissingGlobal("zwlr_layer_shell_v1"))` and nothing opens, `Window` included, even though `Window`,
      `Dialog`, `Popup` and `ContextMenu` speak nothing but `xdg_shell` and would work there. Dropping the
      global from that gate is one line, and the decision is to keep it: a shell toolkit whose bars, docks
      and wallpapers cannot open is not a smaller kortex, it is a different one, and a half-running
      application is worse to diagnose than one that refuses with a reason.
      So the refusal is the feature, and what was missing was saying it. `kortexApplication`'s own docs name
      the requirement and the compositors it rules out, and `:bar` turns the error into a sentence naming the
      protocol rather than printing a data class at a person.
      **`MissingGlobal` carries a `WaylandInterface` now, not an interface name.** It held a `String`, so
      every side that cared which global was missing compared against a literal: `:bar` would have, and
      `WaylandClipboard` already did, to tell a compositor with no `wl_data_device_manager` from any other
      failure. The enum names the seven globals kortex binds with the wire name each is advertised under, and
      `WaylandDisplay.require` takes one, so a typo is a compile error rather than a branch that never runs.
      `WaylandGlobal` stays a `String`, because that one is the compositor's own advertisement and the set of
      those is open. The string a person reads still belongs to the host that prints it, not to `:wayland`.
      Which compositors: the wlroots family (Hyprland, sway, river, labwc, Wayfire) and KWin, which
      implements it (`src/wayland/layershell_v1.cpp:22`, `s_version = 5`). Weston has no layer shell (a
      repo-wide grep of its tree for `wlr-layer-shell` and `zwlr_layer_shell` returns nothing) and neither
      does mutter. This corrects a recommendation made four times during the audit, including in
      `SUMMARY.md`: **running the existing E2E suite under nested Weston is not possible**, and no longer for
      want of a decision. Weston also lacks `zwlr_virtual_pointer_v1`, which is how the suite synthesises
      input, and the harness drives `hyprctl` throughout, so a second compositor stays out of reach on the
      test side whatever the shell side does. (`ExitMessageTest`)
- [x] **Re-rank what each latent finding can still reach, now that the compositor set is known.** Of the five
      the audit called latent on Hyprland, two are unreachable everywhere kortex runs and one is live on KWin.
      Destroy-order `not_the_topmost_popup`: Hyprland does not implement it, KWin has it as a literal
      `// TODO` (`src/wayland/xdgshell.cpp:841`), and mutter enforces it but cannot host kortex, so the fix
      already committed is correct and free but was ranked Critical on the strength of a compositor kortex
      cannot start on. `invalid_surface_state` for a maximized toplevel: KWin's only uses of that code are
      layer-shell and lockscreen, not xdg-shell. The `start_drag` grab serial **is** live: KWin checks
      `hasImplicitPointerGrab(serial)` (`src/input.cpp:2587`) and answers a stale one with `dndCancelled`,
      which kortex handles, so the drag ends cleanly rather than hanging. `wl_pointer.warp` needs `wl_seat` 11
      and nothing advertises it yet. **The serial is fixed**, under "A drag quotes a grab serial that may no
      longer be live" below: the request now leaves from the pointer event that asked for it, with nothing
      encoded in between. The re-ranking itself is what this entry is kept for.

- [x] **kortex's own docs are unreliable in both directions, and three of them licensed defects.** False:
      `LibWayland.kt:272-276`, `:234` and `ProtocolVersionTest.kt:55-58` all claim libwayland enforces
      versions client-side; `DrivenScene.kt:15-17` claims `Dispatchers.Unconfined` keeps work on the calling
      thread, and it does not (`isDispatchNeeded` is `false`, so a continuation resumes wherever it resumes);
      `XdgShell.kt:114-115` gives the wrong reason why its NULL type-table entries are safe. A defect stated
      as a contract: `DataDevice.kt:206`. Correct, specific, names its own remedy, and ignored at five call
      sites: `KortexApplication.kt:49-51` against `bar/Main.kt`'s crash-log effects. Checked and found
      trustworthy: `WlOutput.kt:98-101`. Done, by deleting rather than rewording: none of the false claims
      above is in the source any more.
- [x] **The test suite exercised a frame context the shipping host never uses.** `onScene` passed
      `Dispatchers.Unconfined`, which reports that it needs no dispatch, so work content launched ran at the
      point that launched it rather than off a queue. It now has the shape `SurfaceScene` gives its own
      composition, a real `LoopQueue` under a `SurfaceWork`, drained only by the thread that calls the block
      and closed the way `SurfaceScene.close` closes its own. The block is handed a `tick(frameTimeNanos)`
      that runs the queued work and then the frame, in `KortexShell.serviceSurfaces`'s order; nine call sites
      and seventeen renders moved onto it, `KeyRepeatTest`'s `pollFor` and both `Typist` helpers now drive the
      tick instead of holding a raster. All 352 stayed green, so nothing had been leaning on inline effects.
      `DrivenSceneTest` pins the harness itself and needs no compositor: putting `Unconfined` back fails it.
      The discriminator is a launch into the scope content holds, not a `LaunchedEffect`, whose body Compose
      starts undispatched and which therefore runs inline under either context.

      **This entry's last sentence was wrong.** Deleting `if (scene.hasInvalidations()) onInvalidate()` from
      `KortexScene.render` does turn a test red: `:compose`'s `KortexSceneTest > a draw that invalidates
      itself asks for the next frame`. The line is covered, just not by an `onScene` test, and the entry read
      as a coverage gap where there is none. Checked by deleting it and running everything.
- [x] **`:compose`'s own scene harness rendered from one thread while composing on another.** `withScene`
      gave the scene a single-thread executor as its frame context and drove `setContent` and `render` from
      the JUnit thread, so `measureAndLayout` and `draw` could run against a recomposition on the executor's:
      a shape no host is allowed to take, since a Compose scene is single-threaded. The reason it was not
      `Unconfined` is real and is kept: `FrameRecomposer` rejects a context with no `ContinuationInterceptor`,
      and `Unconfined` satisfies that check while never delivering `onInvalidate` at all. A `SceneLoop` is
      both at once, a dispatcher that must be dispatched to and one only the rendering thread runs, which is
      what `loop + work` is in the host. The block is a `SceneDriver` receiver now, so every `scene.` and
      `surface.` line stands as it was; 32 renders became `tick`, seven waits became `passUntil`, and
      `awaitFailure` passes by default, since an effect that fails after a `delay` resumes on this loop and
      nowhere else. The trap was `stays silent while idle`, which asserts an absence: a window that ran
      nothing would have reported every composition idle. It runs passes now, and a state change landing
      part-way through that window is caught, which is how that was checked.
- [x] **A `@Hotplug` test left no trace in the results, so missing coverage reported as covered.** An excluded
      test writes no `<skipped/>` and no result file at all: `SurfaceScaleTest` recorded `tests="1"` for a
      two-`@Test` file and three whole classes produced no XML, so the run read as complete. `HotplugCoverageTest`
      now names the set, loading each compiled test class without initialising it and reading the annotation off
      the class or the method. It is **seven tests across six classes**, one source more than this entry had:
      `OutputReleaseWireTest` carries a method-level tag nobody had counted. Changing that set is now a
      deliberate act, and its size is the honest headline of every run. The second half is fixed too:
      `SurfaceScaleTest`'s running leg could not fail, because this monitor reports a scale of
      0.9999999999999992 and `ceil()` of that is the value a surface already starts at, so a new leg drives
      `onPreferredBufferScale` straight at the listener instead and needs no second output. The exact mutation
      named here, `preferredBufferScale = DEFAULT_SCALE`, goes red on it. What stays uncovered is the
      delegation behind it, `SurfaceRole.preferredBufferScale`, which only an output at another scale can tell
      from a constant; that is recorded rather than papered over.
- [x] **Compose content gets no `WindowInfo.containerSize`, so every popup clips to the surface's top-left.**
      `KortexWindowInfo` (`KortexScene.kt:310-312`) overrides only `isWindowFocused`, leaving `containerSize`
      at its interface default of `IntSize(Int.MIN_VALUE, Int.MIN_VALUE)` (`WindowInfo.kt:43-50`).
      `Popup.skiko.kt:585-593`'s `clipPosition` then takes the `else 0` branch on both axes for every popup,
      `GraphicsLayerOwnerLayer.skiko.kt:431-441` lights elevation shadows from `lightX` of about -1.07e9, and
      rect tracking gets a negative window. Nothing in kortex or `bar` currently uses `Popup`,
      `DropdownMenu`, `TooltipBox`, `Modifier.shadow`, `Surface(` or elevation, so it cannot fire today --
      and a dropdown in third-party content is the ordinary case, not an exotic one. Compose's own headless
      host sets it (`ImageComposeScene.skiko.kt:160-161`), so a windowless scene is not exempt. Fixed:
      `KortexScene` sets `windowInfo.containerSize` as its size changes.
- [x] **`acceptDragAndDropTransfer`'s boolean is read as a verdict on the session, and it is neither.** It is a
      verdict on the session, correctly, and it was being read as one on the position. The entry's own reading of
      the Compose source holds: the traversal never consults the event's position, and `hasEligibleDropTarget` is
      the accessor for the other question. `KortexScene` now publishes that second question as `dragOverTarget`,
      which reads `hasEligibleDropTarget` and is false as well once content has failed, so a dead scene is over
      nothing. `DataDevice.answerDrag` sends the compositor that answer at the enter and again at every motion,
      which is what `wl_data_offer.set_actions` itself says to do; kortex used to accept a type once at the enter
      and never revise it, so the cursor said "drop here" across the whole surface. `completeDrop` finishes an
      offer only where content took the drop, in place of "some transfer carried bytes": a finish is what lets a
      source dragging a move delete what it sent. It is also a protocol error after an accept of no type, and
      `DataOffer.finish` now holds to both halves of that rule rather than to the action alone. The
      `getOrElse { false }` at the enter stays and is right there: a scene that has failed takes nothing more,
      which is a refusal. Nothing is open on the positive side; the refusing side is an entry of its own
      below. (`DragAndDropTest`, `DragWireTest`)
- [x] **No test drives a drop that content refuses.** `DataDevice.completeDrop` finishes the compositor's
      offer only where content took what arrived, and gives it back unfinished where content did not. Only
      the first of those was covered. That mattered because a finish is what lets a source dragging a move
      delete what it sent, so the branch with no cover was the one that would destroy another application's
      file if it ever sent a finish it should not.
      **It did not need a second client**, which is what the entry used to claim. The refusal has to come
      from content, and content is kortex's either way: a target whose `onDrop` answers false reaches the
      branch, because `dragOverTarget` only asks whether a target exists and `sendDrop` hands back whatever
      Compose returned. What a foreign source would add is types and actions kortex never sends, which is
      `LiveDropProbe`'s job and a separate gap. So `DragWireProbe` took an env var and the same gesture
      serves both legs, differing only in the target's answer.
      Three of the four assertions passed at once: content was handed the drop and declined it, no
      `wl_data_offer.finish` followed, and the offer was still freed. The fourth expected the source to hear
      that the drag had not completed, and it hears a copy, which is the finding:
      **Hyprland sends `dnd_finished` from the offer's own destructor whether a finish was sent or not**
      (`CWLDataOfferResource::~CWLDataOfferResource`), so a declined drop reads to the source exactly like a
      taken one and `onCancelled` never runs. It also sends a drag source no `wl_data_source.action` at all,
      so what content hears is `DataSource.assumedAction`, whose comment already says why that is a copy:
      reporting a move that was not one has content delete what nobody took. A fallback chosen for the
      missing action event is the only thing standing between a declined drop and a deleted file, and the
      test pins both the copy and, separately, that it is never a move.
      (`DragWireTest`, `DragWireProbe`)

- [x] **The crash logger does blocking file I/O on the loop thread, at five sites.** `bar/Main.kt:233-236`,
      `:278-281`, `:333-336`, `:380` and `:384-387` each call `logIfCrashed` inside a `LaunchedEffect`, and
      `appendCrash` (`CrashLog.kt:35-42`) does `Files.createDirectories` then `Files.writeString`. There is
      no `withContext` anywhere in the module. `LoopQueue.runPass()` runs dispatched work synchronously on
      the caller's stack, and that caller is the one thread pumping Wayland and drawing every surface.
      `KortexApplication.kt:49-51` documents exactly this hazard and names `withContext(Dispatchers.IO)` as
      the remedy. It fires when a surface has just crashed, and on a wedged `$XDG_STATE_HOME` mount it
      freezes every surface on every monitor, including ones that never crashed. Fixed: `bar/Main.kt` runs
      `appendCrash` under `withContext(NonCancellable + Dispatchers.IO)`.
- [x] **`wl_pointer.frame` is a no-op, and after checking, that is the right answer.**
      `SeatInput.kt:92` is `= Unit`. `wl_pointer.frame` is `since="5"` against an advertised 9, so it arrives
      on every pointer event group, and `wayland.xml`'s own words are the contract: "A client is expected to
      accumulate the data in all events within the frame before proceeding", with diagonal scroll as the
      worked example. Nothing anywhere in the suite asserts anything about event grouping. Hyprland can
      synthesise the case through `zwlr_virtual_pointer_v1`, which already exposes `axis` and `frame`, so the
      test is writable: `axis(VERTICAL, d)`, `axis(HORIZONTAL, d)`, `frame()`, assert one Scroll event
      carrying both. Done: `onFrame` delivers what a frame group collected, `onAxisSource` records whether the
      device scrolls by distance, and `onAxisValue120` accumulates detents. `onAxisStop` stays `= Unit`, which
      is the right answer: it says a kinetic scroll came to rest, and Compose has nothing to do with that.
- [x] **`bar` is the only module that pins no `jvmToolchain`, and it is the one that ships.**
      `compose/build.gradle.kts:13` and `wayland/build.gradle.kts:13` both carry
      `jvmToolchain(libs.versions.jdk.get().toInt())` with `jdk = "25"`; `bar/build.gradle.kts` has no
      `kotlin { }` block, and nothing in `settings.gradle.kts` or the root build supplies one. So
      `compose.desktop.application`'s `jpackage` step bundles a runtime chosen by whatever JDK is ambient for
      the Gradle daemon, for the one module whose output reaches a user machine. FFM is stable only from JDK
      22, and `--enable-native-access=ALL-UNNAMED` (`bar/build.gradle.kts:20`) means nothing if the bundled
      JRE predates it. Done: `bar/build.gradle.kts` pins the same `jvmToolchain` line its two siblings have.

- [x] **Wheel scroll reaches Compose about fifteen times too fast, and worse on a HiDPI surface.** One detent
      from Hyprland is an `axis` value of 15.0 (`InputManager.cpp`, `delta = 15.0 * discrete * factor`, with
      `value120 = round(factor * e.deltaDiscrete)` alongside it). `SeatInput.kt:82` forwards
      `fixedToFloat(value) * scale`, so Compose is handed 15.0, or 30.0 at buffer scale 2. Compose's own
      desktop host hands it `event.preciseWheelRotation` for the same detent
      (`ComposeSceneMediator.desktop.kt:965-971`), which is in wheel clicks, so about 1.0. Nothing caught it
      because the bar has nothing scrollable in it. The clean fix is `axis_value120 / 120f` for a
      `wheel` source, which reproduces `preciseWheelRotation`'s semantics and is the one thing the currently
      empty companion handlers are good for; it needs `wl_pointer.frame` to pair a value120 with its axis
      event, a separate decision for `finger` and `continuous` sources whose deltas are pixel distances, and
      a decision on whether a detent should be multiplied by the buffer scale at all (it should not: a detent
      is not a distance). Done, all of it: `SeatInput.sendScroll` takes `axis_value120 / 120` for a wheel and
      the pixel delta for a device that scrolls by distance, and `KortexScene.sendPointerEvent` carries
      `preciseScroll` for the axis source. The parenthesis above was wrong as well. `isPreciseWheelScroll`
      reads true for a precise scroll, because the AWT event kortex builds carries the delta as its precise
      rotation against a `wheelRotation` of 0, which the entry on covering `preciseScroll` sets out.
- [x] **Nothing bounded a hand-built `wl_interface` against the XML it was copied from.** The tables kortex
      builds itself carry the version `WlVersion` asks for, so `ProtocolVersionTest` comparing the constant
      against the table it built compared the constant against itself, and nothing caught `LAYER_SHELL = 9`.
      Since the marshal guard reads each request's own version out of the table, a request above the
      negotiated version is skipped rather than sent; what was left is binding a proxy at a version the table
      does not describe, whose events then arrive with opcodes the table has no entry for, which
      `queue_event` answers by killing the connection. This entry said two tables. There are **thirteen**:
      `XdgShell.kt` builds seven, `XdgOutput.kt` two, and only the two named here were ever marked
      `handBuilt` in `ProtocolVersionTest`, so the eleven with the most events in them were the ones nobody
      was looking at. `HandBuiltTableTest` now reads each one's name back out of the table it built and holds
      the whole table against the `<interface>` of that name in the XML the protocol packages ship, found
      through `pkg-config --variable=pkgdatadir`: the declared version against the version kortex binds at,
      and every request and event name and signature in opcode order, with the signature derived from the
      XML the way `wayland-scanner` derives it. `LibWayland.interfaceRequests`/`interfaceEvents` read a built
      table back, and `readRequestSince` now walks the same reader instead of a second copy of the struct
      offsets. All thirteen match today. Proved by mutation: `LAYER_SHELL = 9` fails the version leg naming
      the file, swapping two requests fails the request leg, and dropping the `closed` event fails the event
      leg.
- [x] **A drag quotes a grab serial that may no longer be live by the time it is sent.** Nothing runs between the
      press and the request any more. `KortexSurface.startDrag` marshals on the thread that calls it, which is the
      loop's, since content asks from inside the pointer event that is dragging; the `Dispatchers.Default` encode
      and the post back to the loop are both gone. The payload follows it: `Clip.DeferredImage` encodes as
      `wl_data_source.send` asks for it, on the `Dispatchers.IO` hop that send already took, so an image no
      transfer can carry answers the transfer rather than stopping the drag, and `Clip.bytesFor` carries that
      answer as a typed result rather than a null meaning both "not a type I offer" and "could not encode".
      `KortexPlatform.startDrag` lost its `onNotStarted` callback and answers `Boolean` from the call, which is
      what `startDragAndDropTransfer`'s own `isTransferStarted` reads and what lets Compose offer a refused drag
      to the next `dragAndDropSource` up the tree. `grabSerial` is still deliberately **not** cleared on release:
      clearing it would fail every drag on that same race, including on Hyprland, which validates nothing and
      works today. (`DragAndDropTest`, which asserts the compositor is asked on the calling thread; `ClipboardTest`)

- [x] **`wm_capabilities` is still an empty listener slot, and now it would have a reader.** It is decoded into
      `XdgToplevelListener.capabilities` and published through `WindowStates` and `SurfaceSlot.followWindow`, as
      the entry said it had to be, so a change reaches content by recomposing. `WindowState` reads it back as
      `canMaximize`, `canFullscreen` and `canMinimize`. The default is every capability, not none: the event
      arrives only from v5, a compositor that cannot honour an ask ignores it rather than failing it, and hiding
      a control a silent compositor would have honoured is the worse of the two guesses. `WindowStates` carries
      the set rather than three booleans, so the three `in` checks live at the public accessors alone and there
      is one place for the wrong capability to be named. The `wl_array` walk is now one function, since states
      and capabilities are sent the same way. No live test asserts the published value: Hyprland advertises all
      four, and a window told nothing offers all three, so a live assertion would read the same whether the
      value reached the window or not. (`XdgToplevelCapabilityTest`, and `MinimizeProbe` checks `canMinimize`
      against a real compositor before it asks)


- [x] **A request's own version is read from the table it is declared in, not named at the call site.**
      Fifteen call sites each named the version their request first appeared in, and a constant beside it
      transcribed a fact the message table already carried; two were forgotten, and nothing could see it,
      since only the compositor compares the two and it answers a request below the negotiated version by
      destroying the client. `marshal` now reads the leading digits of the signature the way libwayland
      writes and reads them (`wl_message_get_since`, `src/connection.c`) and compares them the way its
      server side does (`wayland-server.c`), above zero as that check does, since a proxy with no version of
      its own answers 0. Fourteen constants and `marshalIfSince` went with it. One that makes an object is
      refused rather than skipped, because a skipped one hands back a null proxy: kortex declares one such
      request, `create_virtual_pointer_with_output` at `"2?o?on"`, and nothing calls it, so the exemption it
      used to enjoy held by luck rather than by rule.
- [x] **A drag test that could never pass, because its gesture left the surface before it began.**
      `DragWireTest` failed twelve whole-suite runs reporting `no wl_data_device.start_drag left the
      client`, which read as a drag defect and was not one. The probe pressed at the middle of a 64 px
      source box and then moved 40 px to cross Compose's drag slop: the middle is 32 px from the edge, so
      that move always landed outside the surface, and a pointer that leaves gets `wl_pointer.leave` and
      not `motion`. Compose saw `enter`, a press, and a leave, never a single movement, so it never called
      the gesture a drag and never asked the host to carry anything. No `start_drag` was correct; there was
      no drag. The box is 160 px now, so the slop is crossed well inside it, and it is crossed in four
      steps rather than one jump, since a gesture wants several events and one jump is one event.
      Five solo runs and two whole-suite runs green.

      Three things had to go right to see it. The test's own press check was ordered after the
      `start_drag` assertion, so it never ran and never said the press had arrived; it runs first now.
      `traceOf` reported an empty match and an empty capture identically, so 1137 captured lines read as
      nothing captured; it counts what it searched now. And the wire was only ever filtered to
      `wl_data_*`, where the whole story sat in `wl_pointer`. The hand drag that proved the product side
      was right used a 160 px box, which is why it worked where the probe could not.
- [x] **Every event the virtual pointer sent carried a timestamp of zero.** `motionAbsolute`, `button` and
      `axis` each defaulted `timeMillis = 0` and every call site took the default, so every gesture a test
      drove was timed against a clock that had stopped, against the contract
      `KortexScene.sendPointerEvent` states on its own parameter. The parameters are gone rather than
      defaulted differently: the pointer stamps from `System.nanoTime()` itself, so nothing can send a
      constant again. Fixing it exposed a second instance, this one in product code: `SeatInput` sent
      `Enter` and `Exit` with a hardcoded `0L`, because `wl_pointer.enter` and `leave` carry no time of
      their own, and a real clock on motion would have made those two jump **backwards** between the
      motions around them, which is what a velocity tracker reads as a gesture. They now carry the newest
      stamp the pointer has been handed, converted in one place. `PointerClockTest` drives two motions a
      real pause apart and asserts the pause survives into the composition; putting the constant back fails
      it with both stamps printed. Nothing else changed colour, so the answer to "which gesture tests were
      passing for the wrong reason" is: none that exist. No test drove a double click, a long press or a
      fling, which is why a stopped clock cost nothing and why it went unseen.
- [x] **All three of this stretch's fixes have a test of their own now.** `bareSurface` is covered by the
      harness taking an `onStartDrag` whose default refuses rather than reporting success, recorded under
      "Keyboard and clipboard". `askMinimized` has `MinimizeWireTest`, which reads `set_minimized` off a real
      drag of the wire and asserts no other window request went with it, the only oracle there is, since
      `xdg_toplevel` carries no minimized state and Hyprland keeps no minimized windows for `hyprctl` to list.
      **`preciseScroll` was written down here as possibly uncoverable, and that was wrong.** The claim was
      that Compose decides precision through `LocalScrollConfig`, which is `internal`, and that a finger
      source and a wheel source deliver the same magnitude, so neither side of the seam can tell them apart.
      The magnitude is the same, and it is not what Compose reads.
      `DesktopScrollConfig.isPreciseWheelScroll` asks `abs(preciseWheelRotation - wheelRotation) > 0.001` of
      the AWT event, and `preciseWheelEvent` builds one carrying the delta as the precise rotation against a
      `wheelRotation` of 0, so any delta at all reads as precise. That sets `shouldApplyImmediately` in
      Compose's `MouseWheelScrollNode`, which decides whether content animates the scroll or applies it at
      once, and content's own scroll offset is where the two show apart. `LocalScrollConfig` being `internal`
      never mattered, because nothing has to read it.
      `KortexSceneTest` scrolls 3.5 steps into a `verticalScroll` and samples the offset over twelve frames:
      by distance it stands at 28 on the first and stays there, in detents it climbs 6, 22, 28. Both settle
      on the same offset, so the test reads their pacing rather than their distance, and ignoring the flag in
      either direction fails it. (`KortexSceneTest`)


- [x] **A request sent on a proxy that is not there fails here, rather than in libwayland.** `marshal`
      handed its proxy straight to `wl_proxy_marshal_flags`, which reads the interface pointer at offset
      zero before anything else, so a null one was a SIGSEGV that took the process and named nobody: two of
      the three crash dumps in `wayland/` are exactly that, both `wl_data_source.destroy` on a source the
      clipboard was left holding after an assertion failed, faulting at `mov (%r11),%rsi` with `R11` zero.
      `7983b24` fixed the test that left it there in September; the request path itself stayed able to kill
      the JVM for any other caller, `proxyGetVersion` dereferencing the same null a line earlier.
      `NullProxyRequestTest` pins the refusal.
## A blind review of `:dbus`, `:tray` and `:notification`

Six agents read the three new modules and the session's `:wayland` changes, each told what the code is and
nothing about what it was expected to do. What came back was checked against the code before any of it was
believed, which is where the false positives went: an "inconsistency" that an existing test pins as
intended, and a "divergence" whose two paths converge by design. Six findings survived, one more turned up
while they were being fixed, and all seven are fixed below. Six carry a test, and every one of those six
was run against the unfixed code to watch it fail there. The seventh has no test and says why where it is.

- [x] **A match rule naming a sender discarded every signal it had just asked for.** `asExpression` sends
      `sender='org.kde.StatusNotifierWatcher'` and the bus resolves that name to the connection owning it,
      then stamps what it delivers with that connection's **unique** name. `matches` compared the rule's
      string against the stamp, so a rule naming the only form a caller can know ahead of time matched
      nothing. Found by three agents independently. Sender is now the bus's to judge and is left out of
      `matches`, which is documented along with its one cost: two rules on one connection differing only in
      sender no longer tell each other's signals apart, so narrow by interface, member or path.
      **The test that should have caught it stated the defect as the contract.** It was called `a sender is
      matched against the unique name the bus filled in` and only ever passed unique names, so it passed. It
      is renamed for the contract and carries the well-known-name case that fails without the fix.
- [x] **Two header lengths were added without being checked, and the sum wrapped.** `lengthOf` reads a body
      length and a fields length, refuses a negative one, then adds them: both near `Int.MAX_VALUE` gave a
      small negative total that passed the 128 MiB cap and was handed on as how much more of the message to
      read, reaching `ByteBuffer.allocate` as a negative or near-`Int.MAX_VALUE` size. Each half is now
      capped before the sum, so the sum cannot overflow. (`MessageTest`)
- [x] **Nothing bounded how deep a message could nest.** `readVariant` reads a signature out of the message
      and calls back into `readValue`, so three bytes of body buy a frame of recursion each and no signature
      length bounds it. A `StackOverflowError` is not a value a caller can be handed, which is the one case
      where refusing is keeping a promise rather than second-guessing a caller. Refused past 64 containers
      across a whole message, which is the specification's own limit and not a number kortex chose, as
      `DBusError.NestingTooDeep`. (`MessageTest`)
- [x] **An array's element could read past the array and the value was kept.** `readSequence` works out where
      the array ends but each element is bounded by what is left of the **message**, so an element declaring
      more than the array holds consumed the bytes after it and the loop exited with a value assembled out
      of its neighbour. Now refused unless the elements end exactly where the array said they would.
      (`MessageTest`)
- [x] **The coroutine reading the socket had no catch, so a connection could go deaf without going dead.**
      `pump` launches on a `SupervisorJob` with no handler: anything the decoder threw ended it with
      `death` unset, so no waiting call was failed, nothing read the socket again and every later call spent
      its full timeout finding that out. Routed to `finish` as `DBusError.ReaderFailed`, errors included,
      since a decoder that overflows the stack or runs out of heap is exactly the case this exists for. And
      `call` now asks whether the connection is already dead before sending, which is what makes `finish`
      mean anything to a caller who arrives afterwards.
      **This is the one with no test.** Provoking it needs the decoder to throw, and every way it could has
      now been given a typed error instead: the three fixes above are what used to reach it. A test would
      have to fake a socket to get there, which is the one thing `:dbus` decided against.
- [x] **Any peer on the bus could empty the tray, and a real item's updates were being dropped.** Two halves
      of one mistake. The watcher rules named no sender, so a forged `StatusNotifierItemUnregistered` from
      anybody was routed in and acted on; and `addressOf` compared the stored service name against
      `signal.sender`, so an item the watcher lists under a well-known name, which
      `org.kde.StatusNotifierItem-2362-1/StatusNotifierItem` in `TrayItemTest` is one of the spellings of,
      never matched its own `NewIcon` and never updated.
      Fixed from both ends. A sender is pinned wherever exactly one is legitimate, which is each watcher's
      own rules and the bus's own `NameOwnerChanged`; the bus resolves a well-known name there and routes
      only its owner's signals. And each item now carries the unique name of the connection behind it,
      resolved once at discovery, which is what an item's signals are matched against. The key stays the
      watcher's spelling, because that is what the unregistration will name, by which time the owner cannot
      be resolved any more. The item interfaces themselves can still name no sender, since which connections
      hold items is not known when the rules go up, and that is now harmless: a peer's forged `NewIcon` is
      routed here and discarded for owning no item.
      **The forged unregistration is a test, and the mutant proves it.** With the sender taken back out of
      the watcher rules, `TrayLiveTest` fires a `StatusNotifierItemUnregistered` from a second ordinary
      connection and the tray drops a real item: three items became two, so this was never hypothetical.
      The test holds a collector open across the forgery, which it has to. `items` runs only while somebody
      is subscribed, so the first version of this test read the tray twice, tore it down in between, and
      passed against the unfixed code because nothing was listening when the forgery landed.
      **The owner half is unproven on this session rather than untested.** Every item on this bus registers
      under a unique name, which is `addressOf`'s working case either way, and resolving the owner is what
      the whole suite now exercises. Proving the broken case needs an application that registers a
      well-known name, and none of kdeconnect, Steam, Discord or wine does.
- [x] **A notification replaced by an identical one reached nobody.** `Notification` is a data class and
      `notifications` is a `StateFlow`, so an application replacing a notification with the same content
      produced an equal list and a collector was never handed it. A progress notification that has not moved
      yet sends exactly that. `Notification.revision` counts the postings of an id, so the second is a
      different value, and it is also what a caller animating or timing one needs in order to start again.
      The test is in the `@TakesTheName` class, so it runs with `-Pkortex.notificationTests=true`, and with
      the revision pinned to the first one it is the only one of the fifteen that fails.

What the review asked for that is deliberately still open:

- **There is no `system()`.** `DBusConnection` opens the session bus only, and UPower, logind,
  NetworkManager, BlueZ and systemd, which is most of what a status bar wants, are all on the system bus.
  Those providers come later and the connection grows a system address with them, rather than ahead of a
  caller. The address is `DBUS_SYSTEM_BUS_ADDRESS` when it is set, which it is not on this machine, and
  otherwise the specification's default of `/var/run/dbus/system_bus_socket`, which here is
  `/run/dbus/system_bus_socket` through a symlink. Nothing else about the client changes.
- **The API-shape suggestions, as suggestions.** A `watch(rule)` that adds and removes a match rule with the
  subscription instead of leaving both to the caller; an object proxy so a path and interface are named once
  rather than per call; `argN` on `MatchRule`, which is what narrowing `NameOwnerChanged` needs; public
  constants for the standard error names a caller compares `CallFailed.name` against; and the reading side of
  `introspect()`, which is written and answered but never parsed. Each reshapes a public surface that two
  modules now depend on, so each is a decision rather than a fix.
- **`maven-publish` and the single-target source layout are not findings.** kortex is JVM-only by design, and
  publishing waits until development is done.

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
- An xdg-shell-only mode, and with it Weston and GNOME. `Window`, `Dialog`, `Popup` and `ContextMenu` speak
  nothing but `xdg_shell` and would run there if the startup gate let them, and dropping `zwlr_layer_shell_v1`
  from `SURFACE_GLOBALS` is one line. A shell toolkit whose bars, docks and wallpapers cannot open is a
  different toolkit rather than a smaller one, and a half-running application is worse to diagnose than one
  that refuses with a reason. kortex requires the layer shell and says so instead: the entry under "Audit
  against the reference implementations" holds the working, and the cost this gives up is a second compositor
  to test conformance against.
- Answering a drag that settles on `ask`. **The entry this replaces had the protocol backwards**, and the
  correction is the whole decision: `wl_data_offer.action`'s own description puts that menu on the
  *destination*, "e.g. popping up a menu with the available options", not on the compositor. So answering an
  ask means kortex drawing a menu mid-drop, and kortex draws no widgets: `SurfaceConfig.contextMenu` and
  `MenuAnchor` were deleted for having no caller. Asking the caller to choose instead has nowhere to live
  either, because Compose's own contract has no room for it: `DragAndDropTarget.onDrop` answers a `Boolean`
  and `DragAndDropTransferAction` has no `ask`.
  The second reason is independent of the first, and it is why there is no way to develop this here anyway.
  Hyprland 0.56.2 installs **no handler at all** for `wl_data_offer.set_actions`, so a destination's mask is
  discarded, and it selects from the source's mask alone: move if offered, else copy, else move with a
  "Client bug?" log (`src/protocols/core/DataDevice.cpp`, `CWLDataOfferResource::sendData`). `ask` has no
  branch, so it can never be selected for a native Wayland drag, and there is nothing to answer.
  **Nothing needs fixing in the code, which is why this is a decline and not work.** `TAKEABLE` leaves `ask`
  out, `asDragAction` and `asCompletedAction` read it as a copy and as no completion, and `finish` stays
  silent on it because sending one before answering an ask is a protocol error. The offer is then destroyed
  with the drag, and the protocol names that destroy *dismissing* the ask, so an ask kortex cannot answer
  releases its source rather than leaving it waiting. All four enum entries stay, so nobody reading it
  mistakes the wire for having three.
  The corollary is worth keeping in view: since the destination's mask is discarded here, `asDragAction`'s
  clamp is the only thing between content and a delete it never agreed to, not a guard on top of a
  compositor that would otherwise have honoured it. `DragAndDropTest` pins it and now says why.
- Placing a menu ourselves. `SurfaceConfig.contextMenu` flipped a layer surface to whichever corner kept a menu
  on screen, which is the compositor's own job once a menu is an `xdg_popup` and it solves the positioner.
  Deleted with `MenuAnchor` and its nine test cases rather than given a caller.
- A title bar and a theme for it. kortex draws no decoration and refuses a window the compositor will not
  decorate, with `KortexError.ClientSideDecorationRequired`. Every compositor kortex can start on decorates
  server side, because GNOME is the one mainstream compositor that refuses to and it has no layer shell to
  start on, so the drawn version would be code no supported desktop exercises. The window controls are not
  part of this and already exist: `askMaximized`, `askFullscreen`, `askMinimized` and `declineClose` are
  public, `wm_capabilities` says which the compositor will honour, and content draws its own button for each.
  Moving and resizing a window are not part of it either; they have an entry of their own and are wanted.

## Archived

Not reproducing, and kept for the evidence rather than the task.

- **A live `wl_pointer`'s listener was called after its arena closed, once, on 23 September 2026.**
  Archived rather than closed: it has not happened again, and nothing was changed that would have
  stopped it. Ten whole-suite passes and thirty runs of `PointerReleaseOrderTest` on its own did not
  reproduce it, and no `hs_err` file has appeared since. Kept in full because the dumps are the only
  evidence there is and they are git-ignored. Reopen it if a `wayland/hs_err_pid*.log` turns up whose
  faulting frame is `libffi` under `wl_display_roundtrip`, or if a test worker dies with no test
  failure to account for it.

  The three `hs_err_pid*.log` files in `wayland/` have been read. They are two separate faults, not
  one.

    The pair on 14 September (12:23 and 12:27) are **not** a stub-lifetime fault and are already fixed.
    Both are `ClipboardTest.a clipboard that owns the selection has text to paste before the compositor
    ever grants it` reaching `WaylandClipboard.close()` -> `DataSource.destroy()` -> `marshal` with
    `RDI=0`, `RSI=1`: `wl_data_source.destroy` (request 1) sent on a **NULL proxy**. The faulting
    instruction is `wl_proxy_marshal_flags+0x4e`, `mov (%r11),%rsi`, reading `proxy->object.interface` at
    address zero. `7983b24` fixed it the minute after the second one, by unsetting the proxy-less
    `DataSource` in a `finally` so a failing assertion cannot leave it as the clipboard's source. What the
    commit did not do, and what is still true, is stop `marshal` from killing the JVM when a caller hands
    it `MemorySegment.NULL`: `proxyGetVersion` dereferences it first and segfaults just the same. One
    `check` at the top of `marshal` turns a process kill with no Java frame into an ordinary failure.

    The one on 23 September is the real thing, and it is in code that is still in the tree unchanged.
    `PointerReleaseOrderTest` crashed at bytecode offset 129 of its own block, which disassembles to the
    `pumpOrFail(SETTLE_MILLIS)` **immediately after `pointer.release()`**, with nothing between them but a
    `clickAt`. Two milliseconds before the fault the JVM logged
    `HandshakeAllThreads (CloseScopedMemory)`, which is what `Arena.ofShared().close()` raises; every
    other thread in the dump is `_thread_blocked`, so the test worker closed that arena itself. The fault
    is `ffi_call` jumping to `0x0000000100000004`, reached from `wl_closure_invoke+0x14e`
    (`mov (%rax,%rcx,8),%rsi`, `implementation[opcode]`). From `wl_closure_invoke`'s own frame:
    `target` = `0x7fed22e12b10`, whose interface pointer resolves to `wl_pointer_interface` in
    `libwayland-client.so.0.26.0`, and `opcode` = 5, which is `wl_pointer.frame`. So libwayland called
    slot 5 of a pointer listener whose backing memory had just been freed and reused.

    What that rules out, checked rather than assumed: the array is not read past its end
    (`EVENT_COUNT = 12`, `FRAME = 5`, all twelve slots filled); `PointerInput.install` is called once per
    instance and from one place only; `marshal` never passes `WL_MARSHAL_FLAG_DESTROY`, so nothing is
    destroyed twice; `WaylandDisplay.require` re-binds rather than caching, so the surface's seat and the
    test's own seat are genuinely separate proxies; and the shipped 1.26.0 binary does carry the
    destroyed-proxy guard (`dispatch_event+0xca`, `and $0x2` on `proxy->flags`,
    skipping the invoke), so a released pointer's queued events really are dropped.

    Which leaves the step that is not yet explained: the proxy libwayland dispatched to had the destroyed
    flag **clear**, so it was alive, yet its listener array had been freed. `PointerInput.release()`
    destroys its proxy before closing its arena and has done since `d14546a` on 10 September, so on the
    code as written that cannot happen. The ordering is unchanged today, and the five commits that have
    touched `SeatInput.kt` since are version lookup, grab serial, scroll units and comments.

    Both instruments were run and neither reproduced it. Thirty runs of `PointerReleaseOrderTest` under
    `--tests` with `--rerun` came back clean, but those are the weaker evidence: `settings.gradle.kts`
    sets neither `forkEvery` nor `maxParallelForks`, so the whole of `:wayland:jvmTest` runs sequentially
    in **one** JVM and a filtered run is a fresh worker that has opened and closed nothing else. The
    crash was 17 seconds into a worker that had already run other classes, with six earlier
    `CloseScopedMemory` handshakes behind it and `java.awt.datatransfer.StringSelection` loaded at
    12.238s putting a clipboard test ahead of it; a 4-second solo run reaches none of that. So ten passes
    of the whole task were run too, at 2m12s each, in the state the fault actually happened in. Clean as
    well. Whatever the conditions are, roughly forty minutes of the right kind of running did not meet
    them.

    Until it is pinned, nothing in the suite would notice a recurrence: a Gradle worker that dies this
    way reports as a worker failure, not a test failure. The dumps are still git-ignored and a
    `git clean -fdx` still takes them, but everything above was read out of them, so what they hold
    beyond this entry is the raw stacks.
