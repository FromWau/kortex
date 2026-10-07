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

- [ ] **A tooltip on hover, which turns out to be a question about sizing rather than a missing preset.**
      Asked for by the first bar built on kortex from the outside, where it wants one for the CPU and memory
      widgets and the tray already fakes one by drawing its hovered item's text inside the bar, because
      there is nowhere else to put it. A bar is only as tall as the bar, so a tooltip cannot be drawn on the
      surface that triggers it.
      **`Popup` is already the primitive.** `Popup(at, width, height)` is `PopupCall(grab = false)`: a
      surface placed at a point that takes no grab, which is exactly what a tooltip is, and a tooltip that
      took a grab would steal the pointer it is reacting to. Hover tracking and the offset are the caller's,
      and the demo app already computes an offset that way for `ContextMenu`.
      **What is missing is a surface sized by its content.** Every preset here demands a `width` and a
      `height` up front, and a tooltip's size is whatever its text comes out as: a percentage, a memory
      figure and a tray item's tooltip are three different widths, and a caller cannot know any of them
      before Compose has measured them. A layer surface has to declare its size before its content is
      measured, which is the chicken and the egg, and nothing in kortex resolves it today, so the honest
      shape of this entry is "can a surface be sized by what it draws, and at what cost in frames" rather
      than "add a `Tooltip`".
      Worth settling first, because it is not only tooltips: a notification popup, a menu built from a
      `DBusMenu` of unknown depth, and an OSD whose text is a track title all want it, and all of them
      currently guess a size and clip.

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
      by nothing for a popup that never took a grab, which is every popup kortex makes: weston returns early
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
      tell the two apart. And Hyprland was not silent by choice, since `initiateDrag` logs at DEBUG
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

- [x] **The module build files shared most of their lines, and the probe task was copied three times.**
      `build-logic`, an included build, carries two conventions. `kortex-library` is what every
      multiplatform module shared: the plugin, group and version, `explicitApi()`, the toolchain, `jvm()`
      and the test libraries. `kortex-probe` adds the `probe` task. Each module keeps its other plugins and
      its dependencies. `icons` and `bar` stay as they were: one is plain JVM on purpose, the other an
      application. The root build declares `kortex-library` with `apply false`, so the Kotlin plugin loads
      once; without it Gradle warned that a call the plugin makes goes away in Gradle 10. Every module's
      resolved classpaths matched before and after, bar `dbus-test`, which has no tests and now gets the
      test libraries too.

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
still draft is `:mpris`, which does not exist, and the system bus every one of
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
- **Every property reattaches when its source restarts, and one that cannot is a bug.** Start the bar,
  start Steam, its tray icon appears; `pkill steam` and it goes; start Steam again and it comes back. That
  is the standard for everything a bar shows, not a nicety: a shell outlives every application on the
  desktop and usually outlives the daemons too, so anything that latches on first contact is broken by the
  second hour of a session.
  It implies a test shape as much as a design: every provider owes a "kill the source, bring it back" test.
  The places that already obey the rule are exactly the places where somebody wrote one. `rememberMonitors`
  survives an output being unplugged and replugged because the `@Hotplug` tests do that; the interval file
  watcher survives a file being deleted and rewritten because a test does that. Everything found violating
  it below was never tested that way.
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

`:icons` is not in that table because it is not a provider: it is the drawing side of them, and the only
module that depends on a provider and on Compose at once. Nor is `:theme`, which exists now: it watches a
file and hands back colours, so it is drawing-side too, with no provider underneath it at all. Both have
entries below.

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
      **The names were wrong once, and a bar built on it is what showed that.** The two were overloads of
      one `fileWatcher`, both spelled `fileWatcher(path)` at a call site that omitted the interval, both
      returning the same type, and the difference between them being a widget that updates and a widget
      that does not. The error for the wrong choice is a good one, and it is still a runtime one, in red,
      in ninety pixels, among five other widgets. They are `Path.watchText()` and
      `Path.readTextEvery(every)` now: a mechanism cannot be chosen by leaving an argument out, and the
      pair reads beside kern's own `Path.readText()` as read it once, read it repeatedly, or watch it.
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
      **"Only changes arrive" is right for a readout and quietly wrong for a rate, and the docs did not
      say so.** The suppression that makes every text widget simpler means `every` is not the gap between
      values: a file that stops changing emits nothing until it changes again. A consumer's throughput
      widget divided a counter's delta by the interval, which is the obvious implementation, and rendered a
      2 kB keepalive after sixty idle seconds as 1 kB/s instead of 33 B/s. CPU load escaped by arithmetic
      luck, a ratio of jiffy counters where elapsed time cancels, and memory and temperature are absolute,
      so a counter was the only one of four widgets that was wrong. The behaviour is right and already
      pinned by a test; what was missing was a clause, and `readTextEvery`'s KDoc now carries it.
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

- [x] **`:icons` makes a provider's icon drawable, because `Icon(item.icon, null)` is the call site a bar
      author wants.** A new module, plain JVM rather than multiplatform like its neighbours since it reads
      the machine's icon directories and decodes with skia. It owns the XDG theme search, the decode, and
      the unpacking of both raw-pixel layouts, and offers `Icon` for a `TrayIcon`, a `MenuIcon` or a
      `NotificationImage`, plus `rememberIconPainter` for callers who want the painter. It cannot live in a
      provider, which would then need Compose, nor in `:compose`, which would then need the bus.
      **This reverses two decisions taken the same afternoon**, and the reversal is the interesting part.
      Finding the icon was recorded as the caller's scope, and the pixel converters were written and
      deleted as speculative. Both were defensible until the call site was stated: `Icon(item.icon, null)`
      cannot work unless something resolves a theme name, because the only two items on this desktop are
      name-only, so declining the lookup and declining the converters together amounted to declining the
      feature. The deleted converters live here now, where the dependency direction works.
      **`Icon` does not tint**, unlike material3's, because tinting is right for a glyph and wrong for an
      application's own artwork, and it holds its space when nothing resolves so a tray does not reflow as
      icons arrive.
      **A live test found a real bug in the first attempt.** The size model assumed SVG meant size-agnostic,
      so every candidate scored equally and asking for 64 pixels returned the 16-pixel file: Papirus ships
      that icon as three separate SVGs under `16x16`, `22x22` and `24x24`, and only `scalable/` is genuinely
      size-free. The size now comes from whichever ancestor folder names one. A fixture tree would never
      have caught it, which is the argument for testing the lookup against the real filesystem.
      Decoding is `decodeToImageBitmap` and `decodeToSvgPainter` from `components-resources`, a new
      dependency and the only one Compose still points at: `loadImageBitmap` and `loadSvgPainter` are both
      deprecated in 1.12. 12 tests, 5 of them the lookup against this machine's own themes.
      `TrayItem.label` came with it, in `:tray` where the data is: title, then the tooltip's title, then id,
      because Discord sends an empty title and a real id and a host reading only title shows a blank.
- [x] **`:theme` reads a watched JSON file into a `Theme`, so changing the wallpaper retints the bar.**
      matugen already recolours every other themed application on this desktop: 16 templates, 9 of them
      firing a post_hook so the application reloads. A kortex bar is the one that cannot be told, because it
      has no config language and no reload command. `:watch` is the reload command. matugen writes the file,
      the watcher notices, the bar recomposes, and this is the first entry in that config needing no
      post_hook at all, which is the whole argument for the module.
      **JSON, not `.kt` or `.kts`.** A `.kt` is compiled into the bar, so a colour change needs a rebuild,
      which is the opposite of the point. A `.kts` needs the Kotlin compiler at runtime to evaluate a file
      that a wallpaper change rewrites: a compile per theme, and a generated file executed as code. JSON
      needs no new dependency, since kotlinx-serialization is already in the catalogue and in use, matugen
      emits it natively and reads it back with `--import-json`, and nothing in a table of colours wants a
      language.
      **The mapping is total, and that is measured rather than hoped.** matugen emits 50 roles; 48 of them
      are exactly `ColorScheme`'s 48, leaving `shadow` and `source_color` over, diffed against the role list
      in material3 1.9.0's own sources. No role has to be invented or defaulted, and the two left over are
      worth keeping on kortex's type, since a bar may well want `source_color` as an accent.
      **Settled: kortex owns the schema, and a matugen template writes it.** The template is built, and
      renders `{version, mode, wallpaper, sourceColor, light, dark}` with `light` and `dark` each holding
      the 48 roles under their exact `ColorScheme` names, so the Kotlin side needs no `@SerialName`
      anywhere. One file carries both schemes, which is a light/dark switch for free. Parsing matugen's own
      dump instead would couple kortex to matugen's release line, and `--old-json-output` exists because
      that shape has already broken once, while a template is a file the user owns and matugen's engine
      absorbs any change to. Colours are matugen's `alpha_hex`, `#AARRGGBB`, picked because it is
      `Color(0xAARRGGBB)`'s own layout and needs no channel reordering on the way in.
      **Three things the template language will not do, each found by trying it.** `wallpaper` is a plain
      string, because `{{ image }}` renders the literal `Null` when the source is a colour rather than an
      image and neither a filter nor a conditional can make that a JSON null: `replace` panics the binary
      with "not yet implemented", and `if` takes Booleans only. The parser therefore reads `Null` and empty
      alike as absent. A loop over the roles with `camel_case` does work, and is rejected anyway, since
      JSON has no trailing comma to drop and an explicit list pins the schema: a role matugen renames then
      fails loudly at that key instead of quietly reshaping kortex's file. And a template whose input is
      missing makes matugen exit 1 after rendering every other template, so a wrapper that checks the exit
      code refuses to apply a theme that was in fact generated.
      A new module rather than `:compose`, for the reason `:icons` is not in `:compose` either: it needs
      `:watch` and a parser, and `:compose` must no more grow a filesystem than it may grow the bus. It is
      also the one kortex module that commits to material3, because `ColorScheme` is the return type and so
      material3 becomes `api`, where today only `:bar` depends on it. A caller with a design system of their
      own reads the role map and never builds a `ColorScheme`.
      **The hazard is already pinned by a test.** `WatchTextTest` holds `a write in place arrives, whatever
      is read on the way`, because a non-atomic write empties the file before filling it, so a blank or
      half-written read is real. Whether matugen writes atomically is unmeasured and must not be assumed.
      `themeIn` reports such a read as `Unparseable` rather than hiding it, which leaves holding the last
      theme that parsed to the caller: a shell that cares filters the failures out of the flow it shares,
      and one that does not flickers to its fallback for a frame mid-save. Sharing alone does not do it,
      since `stateIn` keeps whatever arrived last, failures included. Surfacing the error somewhere is
      still right, because a bar that silently ignores a theme file is worse than one that says the file is
      wrong.
      Blocked behind the push-watcher bug below, by the reactive rule, and the output path sharpens it: the
      theme renders under `~/.cache`, so clearing a cache takes the watched directory with it, and a
      watcher that dies there means a bar that never retints again for the life of the process.
      **Built, and the one design question it turned on was where the read lives.** `:theme` hands over a
      `Theme`: both schemes and the mode the file asks for, with an `active` that picks between them. Both
      are there rather than only the chosen one because an application may override the mode and then needs
      the scheme the file did not pick. `themeIn(file)` is the public entry point and is a plain cold flow,
      `themeUnread(file)` is its stand-in for the frame before a read, and `rememberFileTheme` is that flow
      collected for one composition. The file schema, the serializer reading `#AARRGGBB` or `#RRGGBB`, and
      the mapping onto material3's 48 roles are all internal, so the published surface is `Theme` and the
      two functions. 19 tests, one decoding the file this machine's generator actually wrote, since a
      fixture only ever agrees with whatever the schema already believes.
      **Three shapes were tried before the last one, and each failed for the same reason.** A `Theme`
      interface whose `isDark()`, `light()` and `dark()` were each `@Composable` read the file once per
      value, so a caller using all three placed three watches on it and the three could disagree mid-save.
      Moving the read to construction fixed that and left `Theme` with no Compose in it at all, which is
      also what makes a built-in theme a plain value rather than an interface implementation with ceremony.
      Then the composable was still the only way in, which put the watcher's lifetime at a call site: two
      monitors meant two bars meant two watches on one file, the same bug one level up. Exposing the flow
      fixed that and matches what every other provider here does. The reading itself was never the problem:
      `watchText` ends `.flowOn(blockingReads)`, so no read ever ran on the thread that draws.
      **Two things measured that contradict what the code was written to believe.** material3 keeps
      deprecated `ColorScheme` constructors taking fewer roles, and the one a short argument list matches
      fills all twelve fixed roles with `Color.Unspecified`, so a mapping that passes 26 of 48 compiles,
      warns, and then draws nothing for them. And `@SerialName` on an enum's entries is honoured without
      `@Serializable` on the enum class, which a mutation run established after a KDoc here claimed the
      opposite. material3's `ColorScheme` also has no `equals`, so two schemes built from one file compare
      unequal, which is why the test for `active` asserts identity.
      **A mutation run also caught a vacuous test of mine**, which is the reason to keep running them: a
      counter placed outside `themeIn` counted collections of the wrapper rather than reads of the file, so
      making the flow hot did not fail it. Stated instead as what a caller observes, that a later collection
      sees the file as it now stands, it catches the regression that matters, a per-path cache with replay.
      What is left is the shell's half. Nothing shares one theme across several surfaces yet, which is the
      `stateIn` the docs recommend, and `:bar` demonstrates the single-surface call instead.
- [ ] **A push file watcher stops for good when the directory under it goes.** `watchText` answers
      `WatchError.Stopped` and completes, and its KDoc calls that intended, which the rule above makes a
      bug: delete the directory holding a config file and recreate it, which an atomic config deploy, a
      `git checkout` of a dotfiles repo and `rm -rf` followed by a restore all do, and the watcher is dead
      for the life of the process. The interval overload is unaffected, because it only ever reads.
      The fix is to watch the nearest existing ancestor and re-register when the path reappears, which is
      what `WatchEnded` should trigger rather than report. `Stopped` then narrows to the cases that really
      are final, and `watchText`'s doc loses the paragraph that called this a feature.
- [x] **`:tray` serves the registry now, so a kortex bar is a tray on its own.** It was the host side only:
      it found an existing `StatusNotifierWatcher`, registered as a host, and answered `TrayError.NoWatcher`
      where none existed, so the tray worked only while some *other* bar was running. On this desktop that
      was ags, and the moment ags stopped the watcher went with it and the live tests went to `NoWatcher`.
      A shell replacing the desktop's only other bar inherited an empty tray with nothing to explain it.
      `TrayWatcher.claim(connection, scope)` takes `org.kde.StatusNotifierWatcher` without queuing, exports
      `/StatusNotifierWatcher` with introspection, answers both register calls and the three properties,
      emits the three signals, and drops an application's items when the bus says its connection went. It
      reuses `ItemAddress.parse(entry, sender)`, so the registry stores exactly the format the host parses
      rather than a second spelling of it.
      **Two successful outcomes, not one and an error.** `claim` answers a `TrayRegistry`: `HeldHere` with
      the watcher, or `HeldElsewhere` naming who has it. Being refused the name is not a failure, because a
      shell that did not get it still draws the tray by reading the registry of whoever did, and a caller
      that read it as an error would stop drawing a tray that works. `Result`'s error channel is left for
      the one thing that really is one, a bus that would not answer. `start` became `claim` with it, since a
      function named start that can answer "somebody else has it" reads wrong at the call site.
      **It does not take the name back and does not wait for one somebody else holds.** Taking a name out
      from under another bar on its next restart is the worse failure, and a desktop running two bars is the
      owner's business. The cost is named: if ags is the registry and ags dies, kortex goes dark for *new*
      items even though it could serve them. For a desktop where kortex is the only bar that never arises.
      **`IsStatusNotifierHostRegistered` answers true from the moment the watcher is up**, rather than
      tracking whether a host has registered. The two wrong answers are not equally wrong: a true with
      nobody drawing costs an application one export nobody looks at, while a false makes it skip the tray
      for the rest of its life. There is a real window between taking the name and a shell's own host
      registering, because `Tray.items` is `WhileSubscribed` and discovers on first collection, so the
      honest answer in that window would be the harmful one.
      **41 tests green with ags stopped, which is the configuration that could not work at all before.**
      The one that mattered is `a host reads a registry its own process is serving`: a shell that serves and
      draws makes a property call to a name it owns, and the bus loops it back through the connection that
      is waiting for the reply. Reading the code said that was fine; only running it proved the pump does
      not sit waiting on itself. `TrayLiveTest` and `MenuLiveTest` now claim a registry in their harnesses,
      so the suite no longer needs another bar running to pass.
      **The probe's two measurements both showed up live.** KDE Connect re-registered with kortex's watcher
      the instant it took the name and appeared as `:1.97/StatusNotifierItem`, so a watcher started after
      the applications adopts the tray already there. `WatcherProbe` is deleted: the real watcher and these
      tests replace it, which is what its own KDoc said to do.
      **Three strings had three homes and now have one.** The two watcher names lived in `Tray`'s private
      companion, in `TrayLiveTest`, and were needed by the watcher, so `StatusNotifier.kt` holds them with
      the path and the member, signal and property names. `"NameOwnerChanged"` is the bus's own signal
      rather than the tray's, so it is `Bus.NAME_OWNER_CHANGED` beside `Bus.NAME` and `Bus.PROPERTIES`;
      `:dbus`'s own tests still have five literal copies of it.
      Left undone deliberately: `WATCHERS` still tries only the KDE and freedesktop names, so this machine's
      activatable `org.x.StatusNotifierWatcher` stays invisible. Probing it would **start** xapp's watcher as
      a side effect of looking, which is a worse thing for a library to do than to miss a name nothing
      registers with.
      **The shape this does not fix**, found while explaining it rather than building it: the registry is a
      singleton whose owner is a volunteer. Whoever starts first wins arbitrarily, every bar has to be able
      to serve it, and when the winner dies nobody is responsible for taking over. The ecosystem's answer is
      on this machine already: `org.x.StatusNotifierWatcher` is **activatable**, so the bus starts it on
      demand and it outlives any bar. A watcher that actually solved this would be both activatable and hold
      the KDE name, since that is the number applications dial; xapp's has the first property and not the
      second, and ags has the second and not the first. Shipping kortex's watcher as its own activatable
      service is the real answer and costs a second process and a service file to install.

- [x] **`:bar` is the QA bar now, which is what finally made `:icons` and `:theme` pay for themselves.**
      There were two bars: `:bar` in this repository, a demo of the toolkit's surfaces with a click counter
      and a theme switcher, and `~/Projects/kortex-bar-qa`, a bar somebody could actually use, built against
      the published artifacts by an agent working blind. The second had the design and the first had the
      theme, and keeping both meant the real bar could never use an unpublished module.
      Merged into `:bar`, repackaged to `com.fromwau.kortex.bar`, consuming `project(...)` rather than
      published coordinates: the MVI state holder, the procfs widgets over `:watch`, the clock, the focus
      timer, the tray widget with hover text, and the notification popup. 11 tests became 60.
      **The demo surfaces are kept rather than dropped**, in `ui/Demos.kt`, behind a right click on the bar.
      They cost no coverage either way, since `:wayland` tests popups, windows and dialogs directly, but a
      repository with no example of opening one leaves a reader to the tests. `BarMenu` never had a way to
      open the window, because the old bar opened it from an inline button, so a menu entry does it now.
      **The theme folded into the MVI rather than arriving beside it.** It was a `var settings` next to a
      state holder that did everything else the other way. `BarScheme` is a sealed set of Light, Dark,
      Amoled and `Custom.Light`/`Custom.Dark`, cycled by `BarAction.SchemeCycled`. The two custom cases are
      the point rather than an afterthought: a theme file carries both schemes and says which it prefers,
      and an application overriding that is a thing a reader should be able to see. `BarScheme.DEFAULT`
      honours `XDG_CACHE_HOME`, so the hardcoded home directory is gone.
      **The state stopped carrying resolved artwork, which is the interesting half.** `TrayEntry.art: Art`
      became `icon: TrayIcon` and `Posted.art: Art?` became `image` plus `iconName`, so the state holder no
      longer reads the filesystem at all and `BusDesktop` lost its `IconIndex`. Resolution happens where the
      drawing does, through `rememberIconPainter`. That deleted `IconIndex`, `TrayArtwork`, `Unpacking` and
      `state/Art.kt` with `Art`, `Pixels` and `IconFormat`, about 380 lines, and 290 more of tests.
      The monogram survived as the bar's own choice rather than `:icons`': `Artwork` takes a `Painter?` and
      draws a letter where it is null. `:icons` holds the square and draws nothing, which is right for a
      library, while a letter saying which application is sitting there is a bar's decision.
      **The swap found two real defects in `:icons`**, which is the argument for doing it by hand rather
      than by deleting files. The bar's own unpacking refused a row stride narrower than one row of pixels,
      and a bits-per-sample that is not 8, and `:icons` guarded neither. The stride one is the dangerous
      half: every other malformed layout runs off the end of the array and is caught by the length check,
      while a narrow stride reads *inside* it with overlapping rows and renders a picture made of its
      neighbours' bytes. Both are guarded, with a test each, so `:icons` is 14 tests rather than 12. Both
      tests were nearly deleted along with the four the move made redundant; checking `:icons`' coverage
      case by case before deleting is what caught them.
      The registry hint lives in `BusDesktop`, which owns the bus connection and is therefore the only
      thing that can claim the name. It claims lazily before building the host, and that order is
      load-bearing: a shell that hosts without ever claiming reads an empty registry on a desktop where
      nothing else serves one, which is a tray that looks fine and is permanently empty. `Desktop` gained
      `trayRegistry: Flow<String?>`, null where this shell is the registry, which the holder collects into
      its own state rather than combining, since `combine`'s typed overloads stop at the five already used.
      **It ran, and three things only running could have told us.** With ags and dunst stopped the bar
      draws, the tray is live, and notifications work. Killing Steam removes its item and restarting Steam
      brings it back, which is the reactive rule satisfied through kortex's own registry rather than
      another bar's, and the thing that could not be demonstrated at all before the watcher existed.
      **The theme switcher was missing, dropped in the merge.** The old bar's button was inline in its
      demo content, so when `BarContent` became the bar nothing dispatched `SchemeCycled` and the state
      field had no way to change. It is a widget now and names the scheme it is showing, since the two
      custom cases differ only in which half of one file they read and a swatch of a theme that failed to
      load looks exactly like one that loaded dark.
      **A reactivity report turned out to be the bar being right**, and still found a real hole. The file's
      `dark.primary` was the blue on screen, so the bar was drawing the current theme. What the report
      exposed was that `ThemeSharingTest` only ever started fresh collections, which proves a new reader
      gets current colours and says nothing about the case a shell actually depends on: a collector that
      never stopped receiving a second value. That test exists now, and `hypr-wal` with the bar up retints
      it without a restart.
      **The bar read four roles of sixteen, which is why a generated theme looked monochrome.** A matugen
      dark scheme puts its whole surface ramp in near-black with almost no chroma, `#10140F` through
      `#323630` on the palette this was found with, and the bar pinned every label and readout to
      `onSurface` at the other end. The colour lives in the container roles it never touched. Widgets are
      chips on `secondaryContainer` now, the clock is the one filled accent on `primaryContainer`, and
      `Label` and `Readout` name no colour at all: they read `LocalContentColor`, which each chip provides
      through `contentColorFor`, so one palette change moves all of them and a label is its own chip's
      colour at 70% rather than a grey that fights it.
      The three hardcoded severity colours went with it. Fixed green, amber and red said the same thing
      under every palette, which is what made the only colour in the bar immune to theming; the bands
      survive because a machine at 90% should still look alarming, but calm and warm are the palette's own
      accents now. The separators went too, since chips have edges of their own and a hairline between
      them is a border on a border.
      A plain background and `CompositionLocalProvider` rather than material3's `Surface`, because
      `Surface` substitutes `surfaceTint` over anything equal to `colorScheme.surface` once an ancestor
      contributes tonal elevation, and a bar should get the colour it asked for.

- [x] **`:hyprland` carries the workspaces and the focused window, read from Hyprland's own sockets.** Built
      as designed below. The first cut only reads: switching a workspace is a command for later.
      **Done.** 21 tests: 19 against `FakeHyprland`, which serves both sockets in a temporary folder and
      answers with JSON recorded from 0.56.2, and 2 live against the running Hyprland, checked against what
      `hyprctl -j` reports. The second live test drives the desktop: it switches to a spare workspace, renames
      it with a comma in the name and switches back to the window that had focus, restoring both in a
      `finally`. Each ordering claim has a mutation that fails it: dropping `focusedmonv2`, the `conflate`, the
      request's `shutdownOutput`, the reconnect, `activewindowv2` or `renameworkspace` each fails a named test.
      **`special` is Hyprland's own range, -99 to -2**, from `CWorkspaceQueryCore::isSpecial`, not a sign
      test: a `name:` workspace is negative too, counting down from -1337, and is not special.
      An instance, as every provider is: `Hyprland(scope, instance = HyprlandInstance.fromEnvironment())`, where
      `HyprlandInstance` is the folder holding `.socket.sock` and `.socket2.sock`, found from
      `XDG_RUNTIME_DIR` and `HYPRLAND_INSTANCE_SIGNATURE`. Taking it as a parameter is what lets a test point
      the provider at a fake. Two flows:
      `workspaces: StateFlow<Result<Workspaces, HyprlandError>>`, with every `Workspace` (id, name, monitor,
      window count) and the active one per monitor, and
      `activeWindow: StateFlow<Result<ActiveWindow?, HyprlandError>>` (address, app id, title, workspace), null
      when nothing has focus.
      **Events are ticks and queries are the truth.** One connection to `.socket2.sock` reads `EVENT>>DATA`
      lines, and a relevant one sends a fresh `j/workspaces`, `j/monitors` or `j/activewindow` down
      `.socket.sock` and publishes what decodes. Workspace events (`workspacev2`, `createworkspacev2`,
      `destroyworkspacev2`, `moveworkspacev2`, `renameworkspace`, `focusedmonv2`, `activespecialv2`,
      `monitoraddedv2`, `monitorremovedv2`) refresh the workspaces; `activewindowv2` and `windowtitlev2`
      refresh the window; `openwindow`, `closewindow` and `movewindowv2` refresh both, since they change
      window counts. `workspacev2` fires only when somebody asks for a workspace, not when the pointer
      crosses to another monitor, which is why `focusedmonv2` is in the list. Folding each event into a local
      copy was rejected: a dozen event kinds to mirror, and a missed one drifts silently. Only the event
      name is read, which also sidesteps the data: its fields are joined by commas that a window title or a
      workspace name may contain itself.
      **Hyprland answers `.socket.sock` synchronously, and the wiki warns that a connection left open
      freezes the compositor until a five second timeout.** So every query opens its own connection, writes,
      reads to the end and closes, and none is ever kept for reuse. The same synchronous dispatch is why the
      wiki asks callers to limit info requests: a burst of events arriving together is coalesced into one
      query per flow rather than one per event.
      `HyprlandError : IError` is `NotRunning` (no signature, or no socket), `NotConnected` (the honest first
      value), `Disconnected` (the event socket closed) and `Unparseable(detail)`. No Compose, no `:wayland`,
      no `:dbus`: JDK 25 opens a unix socket with `UnixDomainSocketAddress` and nothing else.
      Tests: a fake serving both sockets in a temp folder, for events leading to queries and for recovery
      after a disconnect, with no desktop; live tests that only read and compare against `hyprctl ... -j`;
      decode tests over recorded JSON, including the `{}` that `j/activewindow` answers with nothing focused.
      **Probed live on 0.56.2**, by driving a throwaway workspace and a kitty window and recording
      `.socket2.sock` throughout:
      - `j/activewindow` on an empty workspace answers `{}`, and the events say the same with empty data:
        `activewindow>>,` and `activewindowv2>>`.
      - The command socket closes its side as soon as it has answered, whether or not the client shut its
        own, so reading to the end is the whole framing. JSON comes pretty printed with no trailing newline.
        A request it does not know answers the plain text `unknown request`, not JSON, so a non-JSON answer
        is its own error rather than a decode failure.
      - Moving one window to another workspace sent eleven events in the same millisecond, which is the
        burst the coalescing is for.
      - A focused terminal whose title animates (a spinner in ghostty's title) sends `windowtitlev2`,
        `activewindow` and `activewindowv2` about once a second with focus never moving. So the window flow
        requeries once a second for as long as that terminal has focus, and the workspaces flow must not
        listen to title events at all.
      - Renaming a workspace to `a,b` sends `renameworkspace>>9,a,b` and later `destroyworkspacev2>>9,a,b`,
        which is the ambiguous data the event-name-only reading avoids.
      - `[[BATCH]]` separates answers with `\n\n\n`, which the docs never state, so queries go one per
        connection.
      - **`dispatch` is Lua on 0.56**: `dispatch workspace 2` answers a Lua syntax error, and the form that
        works is `dispatch hl.dsp.focus({ workspace = "2" })`, answering `ok`. That is the shape the
        workspace switching command will need when it comes.
      **`:bar` draws both now**, through `Desktop` like the tray. The strip runs from workspace 1 to the
      highest one holding a window or shown on a monitor, gaps included, and one empty workspace past it, so
      windows on 1 and 4 draw 1 2 3 4 5. Special and named workspaces stay out of it: a special one is shown
      over another rather than in a place of its own, and a named one has no number to stand at. The pill a
      focused monitor shows is filled, one on another monitor is tinted, and an empty one is faded. Display
      only, so clicking a pill switches nothing yet. Beside it, the focused window's app id, cut to its last
      segment, and its title, cut short rather than pushing the right side off the bar. (`HyprlandReadingsTest`,
      `BarStateHolderTest`)

- [x] **`:hyprland` runs dispatchers too, and a workspace pill is a button.** `focusWorkspace(workspace)`
      asks for any listed workspace the way Hyprland's selector names it, `special:magic`, `name:web` or the
      number, with the name escaped as a Lua string. `focusWorkspace(number)` is for numbered ones that may not
      exist yet, and refuses a number below 1 as `NotNumbered`: Hyprland's parser
      (`getWorkspaceIDNameFromString`) reads a leading `-` as a move relative to the current workspace and
      clamps the result to 1, so `-98` would have gone to workspace 1 rather than the special workspace.
      `dispatch(lua)` sends whatever Lua it is given. All of them go down
      `.socket.sock` as one short connection like every query. Hyprland's `ok` is `Ok`, and anything else comes
      back as `Refused` with Hyprland's own answer, which for Lua it cannot run is Lua's error. Neither returns
      the new state, which arrives through the flows. Through the provider rather than `hyprctl` in a shell:
      0.56 changed `dispatch` to Lua, which broke every `hyprctl dispatch workspace 1` in a script that does
      not read the answer and is one line here, and a name spliced into Lua inside a shell is an injection
      waiting to happen.
      On the bar a click sends `WorkspaceClicked`, and the empty slot past the end creates its workspace.
      (`HyprlandTest`, `HyprlandLiveTest`, `BarStateHolderTest`)
- [x] **`:hyprland` groups workspaces under their monitor, and follows urgency, specials, the submap and the
      layout.** `monitors` replaces `workspaces`: each `Monitor` holds its own workspaces, specials included, and
      says which it shows and which special one is open over it, so a bar picks its monitor by connector instead
      of matching string-keyed maps. A workspace moved between monitors is a `moveworkspacev2`, which reads both
      lists again, so the grouping is whatever Hyprland says now. `Monitor` carries only what Hyprland decides
      and announces. Its size, scale and position stay with `:wayland`: Hyprland 0.56.2 posts no event when
      they change (every `postEvent` in its source was listed), so a copy here would go stale silently.
      **Urgency is the one fact kept from events.** Hyprland answers no query with it: it marks a window on
      `urgent` and clears the mark when the window takes focus (`FocusState.cpp`), so the flow follows
      `urgent`, `activewindowv2` and `closewindow` by address, ahead of the folding so none is lost, and asks
      `j/clients` which workspace an urgent window is on only while one is. A window that was urgent before
      anybody collected is not known to be.
      `submap` reads `j/submap`, a bare JSON string that is `"default"` for none, and `keyboardLayout` reads the
      keyboard `j/devices` calls main, its code being that keyboard's layout list at its active index, which
      Hyprland writes as a bare `none` when it has none. **Which keyboard is main changes without an event**:
      Hyprland moves it to whichever device last sent a key (`CSeatManager::setKeyboard`, called from
      `InputManager.cpp` on every key) and posts no `activelayout`, so after typing on another keyboard the
      flow names the old one until some layout changes. Left as a documented limit; a query on every key
      would be the cost of closing it.
      On the bar each strip is its own monitor's: its numbered workspaces, the gaps no other monitor holds and
      the next number no other monitor holds, which may be one of its own empty persistent workspaces, so one
      monitor draws what it did before. A pill fills with the error container while a window on it is urgent,
      which lasts until that window takes focus rather than until the workspace is visited, as in Hyprland, an
      open special workspace shows as a named pill after the numbers, a
      `MODE` chip appears only outside the default submap and `KB US` sits beside the clock.
      (`HyprlandTest`, `RepliesTest`, `HyprlandLiveTest`, `HyprlandReadingsTest`, `BarStateHolderTest`)
- [ ] **The bar's surface vanished once, on a click on the clock, and nothing reported it.** 2026-10-02, on the
      build with the per-monitor strip. No "The bar stopped" popup appeared, so the surface never reached
      `SurfaceStatus.Ended`; nothing reached the crash log, which had never been created, or stderr. The JVM
      stayed up with its event loop idle in `awaitWork`, `hyprctl layers` listed no `kortex` surface at all, and
      HDMI-A-2 was connected and enabled; Hyprland's last disconnect and reconnect of it came before that bar
      started. So either `rememberMonitors()` went empty, which draws no bar and no popup, or the layer surface
      went away without its state hearing of it. Restarted under `WAYLAND_DEBUG=client` and clicked the same
      way, it did not happen again. Unexplained; the next occurrence should be caught with the process left
      running and protocol logging on, since the wire shows which of the two it was.
- [ ] **Typed `:hyprland` commands for the actions a bar might run, taken from a real `bindings.lua`.** Designed,
      not agreed. Each sends one `dispatch` and answers `EmptyResult`; a command on a window takes
      `window: WindowAddress? = null`, null meaning the focused one. `exec(command)` (`exec_cmd`, so Hyprland
      starts it and it outlives the bar, unlike `shell`), `closeWindow`, `fullscreen(mode, change)`,
      `float(change)`, `pin(change)`, `moveWindow` to a `Workspace` or a number with `follow`,
      `toggleSpecial(name)`, `focus(direction)`, `swapWindow(direction)`, `resizeWindow(dx, dy)`,
      `cycleWindows(forward)`, `layout(message)` and possibly `exit()`.
      `Change` is `Toggle`, `On`, `Off`, because the dispatchers disagree on the words: `fullscreen` takes
      `action = "toggle"/"set"/"unset"` while `float` and `pin` go through `parseToggleStr`, which knows
      `"toggle"`, `"enable"`/`"on"` and `"disable"`/`"off"` and reads anything else, `"set"` included, as a toggle
      (`LuaBindingsInternal.cpp`, 0.56.2). Open: whether `exit()` belongs at all, a bar button being one misclick
      from ending the session. Tests planned against the fake socket for every command's Lua, and live against
      a probe window `exec`'d onto a spare workspace and closed by address, never one of the user's own.

- [x] **`:shell` is the escape hatch for everything no provider covers.** `shell(script, timeout)` runs
      `bash -c` and answers `Ok(ShellOutput(stdout, stderr, exitCode))` for any script that ran to its end,
      since `grep` finding nothing exits 1 and that is an answer. `ShellError` is only for one that could not
      start (127) or ran past its timeout (124), each carrying the code a shell would report. Stdin is empty
      and both pipes are read at once. No default timeout: a ceiling the caller could not raise would be
      policy.
      **The script owns everything it starts.** It runs under `setsid`, so it leads a process group of its
      own, and when it exits, times out or its caller is cancelled, the whole group is killed with one signal,
      `cmd &` and nested background jobs included. A review found the first version could not keep its
      promises: it killed a snapshot of the process tree, which a child started afterwards escaped, and it
      only timed and cancelled the wait for bash, so a `cmd &` left holding stdout kept the call waiting past
      its timeout (`sleep 4 & sleep 0.3` with a 1 s timeout took 4 s, three runs of three). Waiting for such
      children the way `$(cmd &)` does was the first choice and cannot be made reliable here: once bash exits
      the JDK keeps whatever its pipe already holds and closes it, unless a read is already blocked on it, so
      the same script either waited or lost the output depending on timing. A process meant to outlive the
      call is started with `setsid` and its output redirected. (`ShellTest`, 11 tests, each kill and the
      concurrent read checked by a mutation that fails it)

- [x] **The connection never reconnects, so a bus restart kills every provider for good.** Done in four
      steps, recorded below. `death` is set once and is final: `call` fails fast on it, `send` reports it, and nothing
      reopens the socket. A session bus does restart and a socket does drop, and when it does the tray, every
      menu and the notification server are gone until the process restarts. Worse than gone: `finish()`
      fails the waiting calls but never touches the signal flow, so every subscriber to `allSignals` or
      `signals(rule)` waits forever, and the tray freezes looking fine.
      **No D-Bus library reconnects for you, and the spec says why.** zbus, GDBus, sd-bus, godbus,
      dbus-next, dbus-java and QtDBus all report the close, fail what was waiting, end their streams and
      leave the next connection and everything on it to the app; systemd's own reconnect example closes the
      bus object and runs its whole `setup()` again. A new connection gets a new unique name, which "Message
      Bus Names" says is never reused; a match rule belongs to the connection that added it; "When a
      connection is closed, all the names that it owns are deleted", so another daemon may own one by the
      time kortex is back; and a reply addressed to the old name can never arrive. So the earlier guess, a
      connection that re-applies its own rules and exports, is out: a reconnected connection is a new peer.
      **The design**, the reactive shape kotlinx's own `shareIn` documentation shows for a backend
      connection:
      - `DBusConnection` stays one socket and one life. It gains `closed: StateFlow<DBusError?>`, set in
        the same step that fails the waiting calls, and `allSignals` and `signals(rule)` end at that death
        instead of hanging. That half is a bug fix worth landing first and on its own.
      - A new `SessionBus` in `:dbus` supervises it: `state: StateFlow<BusState>` with `Connecting`,
        `Up(connection)` and `Down(reason, retryIn)`. It opens, publishes `Up`, waits for `closed`, publishes
        `Down`, waits out the backoff and opens again. The backoff starts at 100 ms, doubles to a cap of
        5 s, resets once a connection is up, and is the caller's to replace. It connects only while
        something collects, like every provider, and a test points it at a bus of its own.
      - Providers take the `SessionBus` instead of a `DBusConnection`: `Tray`, `Menu`, `TrayWatcher.serve`
        and `NotificationServer`. Each runs its existing per-connection flow inside
        `state.flatMapLatest`, so every connection starts from scratch with its own rules, subscriptions,
        exports, name requests and first reads. That is the change the `channelFlow`s need least, since
        each already builds everything from nothing.
      - While the bus is down each flow carries a typed `BusDown(reason)`, added to `TrayError`,
        `NotificationError` and the bar's errors, rather than its last value. A command sent then answers
        the same at once.
      - A name kortex owned, the notification server's or the tray watcher's, is claimed again after a
        reconnect. Where another process took it in the gap, that process is reported and the name is
        claimed the moment it lets go, which is the rule `TrayWatcher.serve` already keeps, now for both.
      - A call in flight when the connection dies fails with that death and is never replayed. Whether to
        retry it is the caller's business, which is how RSocket splits the same job.
      - `:bar`'s `BusDesktop` owns one `SessionBus` instead of a lazily opened connection.
      **Tests** follow the provider rule above, "kill it, bring it back", against no fake: the fake bus
      written once was thrown away because it agreed with kortex's mistakes. A test starts its own
      `dbus-daemon` (1.16.2 here, from Arch's `dbus` package) on a private socket, stops and restarts it,
      and checks the `SessionBus` state sequence, signals ending with the connection, the tray reading
      again, a menu reading again and the notification server serving again.
      **Order:** the death made visible first, then `SessionBus`, then one provider at a time, then the bar.
      **Step 1 is done.** `closed: StateFlow<DBusError?>` holds the reason once dead. `allSignals` carries
      `Result<Message.Signal, DBusError>`, each signal as `Ok` and the death as one `Err` the pump sends
      after the last of them, so nothing the bus delivered is cut off; a subscriber arriving after the
      death is given the `Err` at once, and `signals(rule)` completes after it. A shared flow cannot
      complete, which is why the end is a value rather than the flow finishing, and why a channel per
      subscriber was turned down: it would have added a type and a scope to the API for the same guarantee.
      `Tray`, `Menu`, `TrayWatcher` and `serve` stop at the `Err` and report `BusFailed` until step 3 gives
      them `BusDown`, where before they waited forever. `ConnectionDeathTest` runs against a `dbus-daemon`
      it starts and kills itself (`PrivateBus`, kept for the steps after this one). Its mutations fail it
      for the in-band end, the late subscriber and `closed`. The `tryEmit` in `close()` survives its
      mutation, because closing the socket wakes the pump, which usually sends the end before the scope's
      cancellation reaches it; the `tryEmit` is what makes the end certain rather than likely, and the test
      cannot tell the two apart.
      **Step 2 is done.** `SessionBus(scope, backoff)` follows the session bus and `SessionBus.at(socket, …)`
      a bus on a given socket. While something collects `state` it opens a connection, publishes `Up`,
      waits for that connection's `closed`, publishes `Down(reason, retryIn)`, waits and opens again; the
      connection is closed when the last collector leaves. `Backoff(first, cap)` doubles from `first` up to
      `cap`, and a connection that was up sends the next wait back to `first`. `SessionBusReconnectTest`
      kills and restarts its own daemon for every part of that, and each part's mutation fails it; the
      reset needed a test of its own, a bus down long enough to reach the cap before it came up and
      dropped again, since a connection that never failed first is already at `first`.
      The constructors of the four providers change, which is a breaking change to their public API and
      every caller in kortex moves with it.
      **Step 3 is done.** `Tray(bus, scope)`, `Menu`, `TrayWatcher.serve(bus, scope)` and
      `NotificationServer(bus, information, scope)` take the `SessionBus` and run their per-connection
      flow inside `state.flatMapLatest`, carrying `NotConnected` before the first connection and
      `BusDown(reason)` while there is none. A command answers `BusDown` at once while the bus is down.
      `NotificationServer.start` and `stop` are gone: `notifications` is a
      `StateFlow<Result<List<Notification>, NotificationError>>` like every other provider, collecting it
      claims the name, `AlreadyServed` waits for the holder to let go and then takes it, and the last
      collector leaving gives it back. Posted notifications and the id counter outlive a bus restart.
      `SessionBus` now keeps an idle connection for a second and resets to `Connecting` when it closes,
      because `TrayCommandsTest` showed a command right after another getting the replayed `Up` of the
      connection the first one had just closed. `PrivateBus` moved to a `:dbus-test` module, and
      `NotificationServerTest` runs on one, so the `@TakesTheName` gate and `-Pkortex.notificationTests`
      are gone and its 18 tests run in a plain `check`; `notify-send` is pointed at the private bus.
      `TrayBusRestartTest` kills and restarts a bus under the tray, a menu and `serve`. Mutations caught:
      `Down` read as `NotConnected` (both in flows and commands), following only the first connection,
      giving up on a taken name, no `releaseName`, posted notifications dropped per connection, and the
      linger and the reset each. `:bar` does not compile until step 4 moves `BusDesktop` over.
      The two rules the providers shared live once, in `:dbus`. `SessionBus.following(unavailable, …)`
      runs a flow per connection and maps the new sealed `BusState.Unavailable` (`Connecting`, `Down`) to
      a provider's own value, and `SessionBus.withConnection(down, …)` is the command half; each module
      keeps only its one-line mapping to its error type. `DBusConnection.chancesToClaim(name)` is one
      chance at once and one each time the name loses its owner, watching from before the first so a
      holder that lets go is not missed, and owns the match rule; `TrayWatcher.serve` and
      `NotificationServer` both claim through it. `ClaimingANameTest` and two `SessionBusReconnectTest`
      cases pin it, and their mutations (no first chance, the wrong name, a command waiting for `Up`,
      nothing emitted in between) each fail one.
      **Step 4 is done.** `BusDesktop` holds one `SessionBus`, shared by the registry, the tray and the
      notification server, and takes one as a parameter so `BusDesktopTest` runs it on a private bus.
      `BarError.NoBus` is gone, since a bus that is not there is now each provider's `BusDown`, and
      `Format` reads `BusDown` as the bus's own failure and `NotConnected` as "connecting". A bar that
      draws the tray keeps the registry collected with it, so it serves one where nothing else does
      without also having to show who holds it; the old code got the same from building the tray after
      the claim, and dropping that is the mutation `BusDesktopTest` fails. `readable()` makes a
      notification server that is not connected yet `Pending` rather than a failure, as the tray already
      did. `NotificationNameTest` is gone: it read the live desktop's notification daemon, which
      `NotificationServerTest` now covers on a private bus, and a server that waits for the name would
      have taken it the moment dunst exited during the run.

- [x] **The tray follows its watcher, and a shell takes the registry over once its holder lets it go.** Both
      old entries were one rule, "watch the watcher's name and act on it", and it is written once. `track()`
      is a loop now: find a watcher, register as host, read, follow; a `NameOwnerChanged` on the watcher's
      name ends the pass and the next one finds out whether it was replaced or is gone. With none, the tray
      carries `NoWatcher` and waits for a watcher name to gain an owner instead of ending, which is what used
      to leave a bar that started first with no tray for its whole life. Applications re-register with the
      new watcher on their own, so its `ItemRegistered` signals bring the items back.
      Following alone was not enough: when the process holding the registry dies and was the only server,
      nothing serves it again. `TrayWatcher.serve` claims at once where the name is free and otherwise
      reports the holder and claims the moment it lets the name go, and stops watching once it holds it, so
      `stop` still gives it back for good. That is not the takeover ruled out earlier, since nobody holds the
      name at that point. The bar serves with it, and its `tray registry: …` hint now clears when it takes
      over. (`TrayReattachTest`: a tray that starts before any watcher, one that follows the next watcher
      after its own died, serve taking over and the tray following, and stop staying stopped; each with a
      mutation that fails it)
- [x] **Four tests returned a value, so JUnit never ran them.** A `@Test fun x() = runBlocking { … }` whose block
      ends in an expression, `assertIs(...)` or a flow's `first`, has that expression's type rather than
      `Unit`, and the JUnit platform skips a test method that returns something, silently. Three new tray
      tests did it and so did `HyprlandTest.aDroppedEventSocketIsReportedAndThenReattached`, which had never
      run since it was written. Its mutation check, removing the reconnect, had been reported as caught, but
      the `--tests` filter matched no executed test, so the failure was Gradle's "no tests found" rather
      than the test. All four are `runBlocking<Unit>` now, and the reconnect mutation was redone and fails
      the test for real. Comparing each class's `@Test` count against its result XML is the cheap check, and
      every other mismatch in the repository is a `@Hotplug` test excluded by its tag.

- [x] **Seven public commands in `:tray` had no test, and now each does.** `Tray.activate`,
      `secondaryActivate`, `contextMenu` and `scroll`, and `Menu.send`, `aboutToShow` and
      `activationRequests`, the whole half of the module that acts rather than reads. They were skipped
      because driving them against a live item really opens somebody's window. `TrayCommandsTest` exports its
      own item and its own menu on a second session connection and drives those, so nothing on the desktop
      moves, and it needs no watcher since a command goes to the item's address directly. It pins the
      arguments in order (`activate` sends x then y, `scroll` the delta then the orientation), the fallback
      to `org.freedesktop.StatusNotifierItem` for an item that refuses KDE's interface, a refusal on both
      being `BusFailed`, an `Event` carrying its entry, name, empty data and time, `AboutToShow`'s answer
      read rather than assumed, and an `ItemActivationRequested` arriving by its id. Each has a mutation that
      fails it: swapped coordinates, swapped scroll arguments, no fallback, the answer ignored, the time
      dropped and the wrong signal filtered.

- [x] **`DBusConnection.kt` and one piece of it that is a different protocol.** The SASL login now lives
      in `Handshake.kt` as `SocketChannel.authenticate(uid)`, the only code that reads a `\r\n` line off the
      socket. It runs before the connection object exists, so a refused login no longer builds a connection
      and its scope only to drop them. `HandshakeTest` covers a refusal and a bus that hangs up mid-login,
      which nothing covered before.

- [x] **Five providers repeated one loop, and one rule about a service leaving lived in each.**
      `:dbus` owns both now. `connection.watching(rules, ruleFailed) { signals -> }` adds the rules and
      subscribes before the block runs, closes `signals` when the connection ends, ends the flow when the
      block returns, and removes the rules however it ends. `Message.Signal.nameOwnerChange` is a typed
      `NameOwnerChange(name, oldOwner, newOwner)` that only believes the bus itself. `Mpris`, `Upower`,
      `PowerProfiles`, `Tray`, `Menu`, `TrayWatcher` and `chancesToClaim` use them. `WatchingTest` pins
      each promise, and each of seven mutations fails a test.

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

## Parity with Quickshell, and better

Quickshell is the closest thing to what kortex is for: primitives and data back ends, with every visible
piece of UI written by the user. A survey of its upstream v0.3.1 (commit `11ca60b`) gave the items below,
which are what kortex lacks against it. What they say about Quickshell comes from that survey and was not
re-read in its source here. Quickshell is LGPL-3.0, so it is read for structure only and nothing is copied.

"Better" has a fixed meaning across all of these, the rules every provider here already keeps: every source
is a flow carrying a `Result` with typed errors, every command answers whether it worked, nothing runs while
nobody is watching, and every source reattaches when what it reads restarts, with a "kill it, bring it back"
test to prove it. The report's own list of where Quickshell falls short of that is the measure: errors only
logged, fire-and-forget dispatch, optimistic D-Bus writes never rolled back, no restart handling for
UPower and BlueZ, and MPRIS position, Hyprland geometry and file watching that have to be polled by hand.

All of these are drafts: what Quickshell has, what kortex has instead, and the angle that would make kortex's
version the better one. None is designed.

**Wayland protocols**

- [ ] **A real lock screen: `ext-session-lock-v1`, and PAM to unlock it.** Draft. Quickshell has
      `WlSessionLock` with one surface per screen that follows hotplug, a `secure` flag once the compositor
      confirms the lock, and a PAM module that forks a child per conversation. kortex's `LockScreen` preset
      is an overlay that takes the keyboard, which locks nothing. Better: the lock's states and PAM's
      answers as typed values, so a wrong password and a PAM that cannot run are different things.
- [ ] **The desktop's windows from the protocol: `ext-foreign-toplevel-list` / wlr foreign toplevel.**
      Draft. Quickshell's `ToplevelManager` lists every window with title, app id and state, and links each
      to Hyprland's own through `hyprland-toplevel-mapping-v1`. It is what a taskbar is made of, and it works
      on any compositor that speaks it, where `:hyprland` only works on Hyprland.
- [ ] **Screen and window capture: `ext-image-copy-capture`, wlr screencopy, Hyprland toplevel export.**
      Draft. Quickshell's `ScreencopyView` draws a live output or window, which is what window previews and
      a workspace overview are made of.
- [ ] **Idle: `ext-idle-notify`, `idle-inhibit`, and the keyboard shortcuts inhibitor.** Draft. Quickshell
      has `IdleMonitor`, `IdleInhibitor` and `ShortcutInhibitor`: dim or lock after a while, keep the screen
      on during a video, let a game have the keys.
- [ ] **Workspaces from the protocol: `ext-workspace-v1`.** Draft. Quickshell's generic `Windowset` model
      has this as its only provider. It is the compositor-neutral counterpart of `:hyprland`'s workspaces.
- [ ] **Hyprland's own protocols: focus grab and global shortcuts.** Draft. `hyprland-focus-grab-v1` tells a
      layer surface the user clicked elsewhere: a panel or an OSD that should close on an outside click,
      which an `xdg_popup` grab does not cover since they are not popups.
      `hyprland-global-shortcuts-v1` lets a keybind in `hyprland.lua` call into kortex. Quickshell crashes
      the second process that registers the same shortcut; a typed error is the better answer.
- [ ] **Background blur: `ext-background-effect`.** Draft. Quickshell's `BackgroundEffect`. Whether Hyprland
      speaks it is unchecked; it blurs behind layer surfaces through its own layer rules today.

**Compositor IPC**

- [ ] **Hyprland's windows, and typed actions.** Draft. Quickshell keeps `Hyprland.toplevels` beside
      monitors and workspaces; `:hyprland` reads the focused window only. Its actions are a string sent with
      nothing returned, which the typed commands already planned above improve on. That entry stands; this
      one adds the window list.

**Services**

- [x] **The system bus in `:dbus`, with restarts handled.** The prerequisite for UPower, NetworkManager,
      BlueZ, polkit and logind. Quickshell watches some services' names and not others, and UPower and BlueZ
      never recover from a daemon restart there. `DBusConnection.system()` reads `DBUS_SYSTEM_BUS_ADDRESS`
      and otherwise the `/var/run/dbus/system_bus_socket` the specification names, with the same `EXTERNAL`
      handshake. The supervisor is a sealed `FollowedBus` with two kinds, `SessionBus` and `SystemBus`, so
      a provider's constructor says which bus it needs and handing it the other does not compile; restarts
      are handled by the same code for both. `SystemBusTest` reads the real system bus, and proves it is
      that bus by finding logind's name, which the session bus does not carry; opening the session address
      instead fails both of its tests.
- [x] **`:mpris`, with a position that moves.** Quickshell's `position` is deliberately not reactive and
      the docs tell the user to poll it from a timer. `Mpris(bus, scope).players` lists every
      `org.mpris.MediaPlayer2.*` player but `playerctld`, which mirrors the last one used and would show
      its track twice, and keeps each up from `NameOwnerChanged`, `PropertiesChanged` and `Seeked`. A
      `Player` carries a `PlayPosition`, a reading and when it was taken, and `position(player, tick)`
      carries it forward at the player's rate while playing; it is read again on `Seeked` and whenever
      the status, rate or track changes, since `Position` announces nothing of its own. Commands cover the
      transport, seeking, volume, loop, shuffle and raise, and `setPosition` answers `NoTrack` for a player
      that names no track rather than sending a call the specification says it will ignore. Tests run a
      fake player on a private bus, and each of nine mutations fails one; `MprisLiveTest` only reads.
      Not yet drawn by the bar.
- [x] **Power: UPower.** Quickshell has the display device, every device, on battery, and a writable
      profile, and never recovers from UPower restarting. `Upower(systemBus, scope).power` is one snapshot,
      `Power(onBattery, display, devices)`, read from `EnumerateDevices` and kept up from `DeviceAdded`,
      `DeviceRemoved` and `PropertiesChanged`; UPower leaving the bus is `NotRunning`, and its coming back
      is read from nothing. `display` is null on a machine with no battery of its own, as here. The bar
      shows the display battery and every peripheral's, a low one in the error colour, and nothing where
      there is none. Tests run a fake UPower on a private bus, and each of eleven mutations fails one;
      `UpowerLiveTest` only reads, and finds this desktop's mouse and headset.
- [x] **Power profiles.** `PowerProfiles(systemBus, scope).state` is `ProfileState(active, available,
      degraded, holds)` from `org.freedesktop.UPower.PowerProfiles`, kept up from `PropertiesChanged`, and
      `choose(profile)` writes `ActiveProfile`. A profile the machine does not offer is `Unavailable` before
      the daemon is asked, and polkit's refusal is `NotAuthorized`. The daemon leaving is `NotRunning` in
      whatever state it leaves from: a read cut short by its going had left the state at `BusFailed`
      for good, and `Upower` had the same fold, so both changed. The bar shows the active profile, and a
      click goes round the ones on offer. This desktop has only the placeholder driver, so it offers
      power-saver and balanced and switching changes nothing; the laptop is where it gets tried for real.
- [ ] **Network: NetworkManager.** Draft. Quickshell does Wi-Fi and Ethernet, connecting with a PSK, and has
      no secret agent, so a network needing a password it does not have fails as `NoSecrets`. A secret agent
      is where kortex could do better.
- [ ] **Bluetooth: BlueZ.** Draft. Quickshell does power, discovery, connect, pair, forget and battery, and
      has no `Agent1`, so no PIN or passkey flow. The agent is the better-than.
- [ ] **Audio: PipeWire.** Draft. Quickshell binds libpipewire natively for nodes, volume and mute. Whether
      kortex reaches it through FFM, through WirePlumber's D-Bus, or through `wpctl` under `:shell` is the
      first decision.
- [x] **`:socket`, the one place kortex speaks to a Unix socket.** `UnixSocket.connect(path)`, then `write`,
      `finishWriting`, `readExactly`, `readLine`, `readToEnd` and `lines()`, every one a `Result` with a
      typed `SocketError`: `NotFound`, `Closed` or `Failed`. One buffered reader serves all the reads, so a
      protocol that turns from lines to bytes, as the bus login does, loses nothing in between. Cancelling a
      read closes the socket, since the stream is then at a place nobody knows; a write always runs to its
      end, so a message is never sent half. `:dbus` and `:hyprland` use it, each mapping `SocketError` to
      the errors its callers already match on, and `:polkit`'s helper socket will be the third.
- [ ] **A polkit agent: `:polkit`, and the prompt as an app of its own.** Planned. polkitd never takes a
      password from an agent. It calls the agent's `BeginAuthentication` (action, message, cookie, which
      identities may answer), and the agent replies once it is over. The password goes to
      `/run/polkit/agent-helper.socket`, which systemd runs as root: the agent writes the user name and the
      cookie, a line each, then answers the PAM lines the helper sends (`PAM_PROMPT_ECHO_OFF`,
      `PAM_PROMPT_ECHO_ON`, `PAM_ERROR_MSG`, `PAM_TEXT_INFO`) until `SUCCESS` or `FAILURE`. The helper
      tells polkitd itself. All read from polkit 127, the version here, whose helper is not setuid.
      In order:
      1. `:polkit`, a provider with no UI: register on the system bus for the session, export the agent,
         drive the helper, and hand out requests to answer or cancel. Tested against a fake polkitd on a
         private bus; the helper only works against the real one.
      2. Whether a Compose text field gets typed input on a kortex surface with keyboard focus. Unverified,
         and the biggest risk, so a throwaway window settles it before the app. It takes focus, so ask first.
      3. `:polkit-agent`, a small app beside `:bar`, so the password lives in a process holding nothing
         else, and restarting the bar never cancels an authentication. The cost is a second JVM all session.
      Starting it: as a systemd user service, the way `startup.lua` starts `plasma-polkit-agent.service`.
      That process sits in the user manager rather than the seat's session and still serves this
      session, so a service works; which session id it registers for is still worth reading off the KDE
      agent before writing ours.
      Live: one agent per session, and the KDE agent holds this one, so a live run needs it stopped by
      hand, the same as dunst for `:notification`. The password is never logged, and a JVM string cannot be
      wiped, which is one more reason for the separate process.
- [ ] **greetd.** Draft. Quickshell has a greetd client, which makes a login screen. Further out than
      everything above.
- [ ] **`:notification` against Quickshell's server.** Draft. Quickshell opts into capabilities one by one
      (actions, markup, images, persistence, inline reply) and has no expiry timer, leaving `expire()` to the
      user. kortex's server times nothing either, by design: it hands each notification the `Expiry` its
      application asked for, and the bar honours it now: `After(d)` closes after `d`, `Never` stays until
      clicked, one that leaves it to the server stays two seconds, a critical one stays, and the close
      tells the application `Expired` rather than `Dismissed`. Still open here: the capabilities the
      server advertises against Quickshell's.

**Runtime**

- [ ] **Commands into a running shell.** Draft. Quickshell's `IpcHandler` exposes typed functions and
      properties over a socket, called with `qs ipc call`, which is how a keybind opens a launcher or toggles
      a panel. kortex has nothing a keybind can reach.
- [ ] **Desktop entries, for a launcher.** Draft. Quickshell's `DesktopEntries` reads `.desktop` files.
      `:icons` already resolves the icons such a list would show.
- [ ] **Hot reload.** Draft, and the one that does not translate directly. Quickshell builds a new QML engine
      per reload and keeps windows alive across it. kortex is compiled Kotlin, so the question is whether
      Compose Hot Reload can reach content under a kortex surface.

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
- **661 tests on `master`: 397 in `:wayland`, 73 in `:dbus`, 60 in `:bar`, 41 in `:tray`, 27 in
  `:compose`, 20 in `:theme`, 15 in `:watch`, 14 each in `:notification` and `:icons`. 570 of them have
  been seen green in one run, which was before `:icons`, `:theme`, the tray's watcher and the bar merge,
  so no whole-suite run has covered what is on `master` now. Everything outside `:wayland` has been green
  on its own: `:tray`'s 41 with ags stopped, which is the configuration that could not pass at all before
  the watcher, and `:bar`'s 60, `:theme`'s 20 and `:icons`' 14 after the icon swap.**
  **`:tray`'s 41 need whatever holds `org.kde.StatusNotifierWatcher` stopped**, which is `ags quit` on this
  desktop, because the watcher tests claim that name themselves. That is the mirror of the old problem
  rather than the same one: the suite used to need another bar running and now needs it not to be.
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
  **It is a race now, not an open question.** The first failure came in a run while the desktop was in
  use. It failed again on 2026-10-02 in a full `check` with nobody at the desktop, 1 of 397 `:wayland`
  tests, and passed 8 solo runs straight after. libwayland keeps the first error it records and flushes
  past EPIPE but not ECONNRESET (`wl_display_flush`, 1.26.0), and the kernel marks this end ECONNRESET when
  the compositor closes with requests of ours still unread (`unix_release_sock`). So `killConnection` now
  waits for the compositor to hang up before returning, and requests sent after that fail with EPIPE.
  **Not proven**: the old helper never failed in 700 stress runs, idle and with every core busy, so the
  failure could not be reproduced to show the wait removes it. Kept, with a comment on the test that it
  can race.

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
- **`:watch` has no public API dump, and nor does anything else.** A consumer has no way to read kortex's
  surface: no Dokka, no `.api` file, no sources jar, so the first outside user unzipped the jars and ran
  `javap -public`. `binary-compatibility-validator` is about ten lines of build script and leaves an `.api`
  file in the repo, which doubles as the thing a reviewer reads to see a public surface change.
- [x] **`0.dp` meaning "the whole edge" was a sentinel inside a `Dp`, and is now a `Length`.** `WholeAxis`
      and `Of(amount)`, beside `ExclusiveZone`, which had already made the same move for the same reason:
      the protocol's 0 is the protocol's, so it stays there, and the two meanings a caller can hold are
      types. A length that rounds to no pixels is `KortexError.EmptyLength` now instead of silently
      becoming the opposite request.
      **It was in more places than the finding said.** `LayerSurface`'s public `width` and `height` carried
      the same 0, so the fix is four public signatures, not three, and the internal `SurfaceConfig` carries
      the type too, since otherwise `Length.Of(0.dp)` would convert straight back to the sentinel and the
      change would be decoration. `requirePlaceable` reads better for it: it matches on cases rather than
      comparing against `SPAN_ANCHORED_AXIS`, and that constant now appears twice in the whole repository,
      its declaration and the one line that writes the wire, where before it was in fourteen test files.
      **One test would have failed only on the desktop.** `SurfaceRebuildTest` drove a rebuild through a
      `MutableState<Int>` set to 0 to mean "span", which under the new rule is `Length.Of(0.dp)`, so it
      would have ended with `EmptyLength` where it asserts `UnspannableAxis`. The state holds a `Length`
      now and says `WholeAxis`, which is what it always meant.
      **`PlacementTest` is new and needs no compositor.** Seven cases over `requirePlaceable`, which until
      now was only ever exercised through a live surface ending with an error. Removing the `EmptyLength`
      branch fails four of them.
- **The consumer-build story is three things the README had to be told, not one.** Google's Maven for the
  androidx artifacts Compose resolves, `maven.frommhund.xyz` for kern, and a toolchain repository for
  JDK 25, because `foojay-resolver-convention` sits in kortex's own settings and a settings plugin does not
  reach a build that only includes kortex. All three are in the README now. All three were found by
  somebody building against kortex rather than by anybody reading it, which is the argument for doing that
  again when the surface next changes.
- **Nothing says which `CoroutineScope` a shell's state belongs on, and providers made it worse.**
  `kortexApplication`'s KDoc says the content thread also draws, so the reflex,
  `stateIn(rememberCoroutineScope())`, looks unsafe, and nothing in the README, the KDoc or the demo says
  what context that scope gets. The first outside user wrote their own scope plus a `DisposableEffect` and
  a wrapper to carry both, and left the question open rather than reading the implementation.
  Adding `:tray` and `:notification` to that bar turned twelve lines into about twenty-four, because a
  provider introduces a **second** lifetime: the bus connection, the tray and the notification server
  belong to the application, while a surface's `stateIn` belongs to the surface, and the two end at
  different times. A shell whose content is two composable calls now carries two hand-rolled scopes and two
  `DisposableEffect`s. Either answer it in the doc or offer a `rememberShellScope()`, and say which
  lifetime a provider belongs to.
- **A public `pseudofilesystemOf(path)` would let a consumer test its own choice of watcher.** Wanted by the
  first outside user and hand-written instead. Smaller now that the two watchers have their own names, so
  it waits for a second person to ask.
- **The API-shape suggestions, as suggestions.** A `watch(rule)` that adds and removes a match rule with the
  subscription instead of leaving both to the caller; an object proxy so a path and interface are named once
  rather than per call; `argN` on `MatchRule`, which is what narrowing `NameOwnerChanged` needs; public
  constants for the standard error names a caller compares `CallFailed.name` against; and the reading side of
  `introspect()`, which is written and answered but never parsed. Each reshapes a public surface that two
  modules now depend on, so each is a decision rather than a fix.
- **`maven-publish` and the single-target source layout are not findings.** kortex is JVM-only by design, and
  publishing waits until development is done.

## Deliberately not doing

- **X11.** Quickshell backs `PanelWindow` with struts on X11; kortex requires `zwlr_layer_shell_v1` and
  refuses to start without it, and stays that way.
- **A provider for i3 and Sway.** Quickshell's `Quickshell.I3` speaks i3's binary IPC for both, with
  workspaces and outputs but no windows. Hyprland is the compositor kortex follows; the protocol drafts
  (`ext-workspace-v1`, foreign toplevel) are the compositor-neutral route if another is ever wanted.
- **`toImageBitmap()` converters in `:compose` for the two providers' raw pixels.** Written, tested and
  deleted the same hour, which is the useful part of the entry. Compose Desktop already decodes encoded
  images: `loadImageBitmap(InputStream)` is `readAllBytes().decodeToImageBitmap()`, `loadSvgPainter` is
  beside it, and Coil is better than either for anything more. That covers what hosts actually meet, which
  is a tray icon's resolved theme file, a notification's `image-path`, and a menu entry's `icon-data`, all
  of them encoded. The only thing left uncovered is the two **raw pixel** fields, `TrayImage.argb` and
  `NotificationImage.pixels`, which nothing in Compose or Coil can read because raw pixels carry no header.
  That is a real gap and a narrow one: neither item on this desktop sends pixels at all, so the tray
  unpacker a consumer wrote was dead at runtime, and 140 lines of skia pixel work plus eight tests is poor
  value for a path that rarely runs. It would also put a drawing opinion inside `:compose`, which kortex has
  kept out on purpose.
  **What was kept is the half that prevents the bug**: each image type's KDoc now names the other and says
  one unpacker cannot read both. That matters because the failure is silent in the worst way. ARGB opaque
  blue, bytes `FF 00 00 FF`, read as RGBA comes out as opaque *red*: a pure colour rotation at full alpha,
  no transparency hint, no error, just the wrong picture. Bring the converters back the day an item that
  really sends pixels turns up and somebody says so.
- **A glob watcher in `:watch`**: "the first file matching this pattern, watched, and re-resolved if it goes
  away". Every sysfs widget needs to find its file before it can watch one, battery, backlight, temperature
  and fan speed included, and none of them can hardcode a path. Finding it is still the caller's scope, and
  `kern:dirs` is already on the classpath with `list` and `walkTopDown` to do it. `:watch` watches a file
  you can name; what both watchers' docs now say is that naming it is your job, with the hwmon case as the
  example.
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
