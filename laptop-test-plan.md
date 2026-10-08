# Testing on the laptop

The development desktop has one monitor, at scale 1, and no battery. This list covers what that machine cannot
show: a second monitor, a scale other than 1, a real battery and a power profiles driver that does something.
Fill in the results as you go and commit this file back.

## Before you start

- [x] JDK 25 is installed, or Gradle can fetch one.
- [x] Hyprland is 0.56 or later (`hyprctl version`).
- [x] `dbus-daemon` is on the `PATH`.
- [x] GTK is 4.22.5 or later (`pacman -Q gtk4`), which you need before running the hotplug tests.
- [x] The tray has at least one item in it.
- [x] Nothing else holds `org.kde.StatusNotifierWatcher`. On ags, run `ags quit`.
- [x] No clipboard manager or clipboard sync is running, KDE Connect's clipboard plugin included.

Record the machine:

```sh
hyprctl monitors all -j
upower -d | head -40
powerprofilesctl list
```

```text
[{
    "id": 1,
    "name": "eDP-1",
    "description": "LG Display 0x06AA",
    "make": "LG Display",
    "model": "0x06AA",
    "serial": "",
    "width": 3840,
    "height": 2400,
    "physicalWidth": 340,
    "physicalHeight": 210,
    "refreshRate": 60.00000,
    "x": 313,
    "y": 1155,
    "activeWorkspace": {
        "id": 2,
        "name": "2"
    },
    "specialWorkspace": {
        "id": 0,
        "name": ""
    },
    "reserved": [0, 0, 0, 0],
    "scale": 1.3333334,
    "transform": 0,
    "focused": false,
    "dpmsStatus": true,
    "vrr": false,
    "solitary": "0",
    "solitaryBlockedBy": ["WINDOWED","CANDIDATE"],
    "activelyTearing": false,
    "tearingBlockedBy": ["NOT_TORN","USER","CANDIDATE","HW_CURSOR"],
    "directScanoutTo": "0",
    "directScanoutBlockedBy": ["USER","CANDIDATE"],
    "disabled": false,
    "currentFormat": "XRGB8888",
    "mirrorOf": "none",
    "availableModes": ["3840x2400@60.00Hz"],
    "colorManagementPreset": "srgb",
    "sdrBrightness": 1,
    "sdrSaturation": 1,
    "sdrMinLuminance": 0.2,
    "sdrMaxLuminance": 80,
    "hardwareCursorsInUse": true
},{
    "id": 0,
    "name": "HDMI-A-1",
    "description": "LG Electronics LG TV SSCR2 0x01010101",
    "make": "LG Electronics",
    "model": "LG TV SSCR2",
    "serial": "0x01010101",
    "width": 4096,
    "height": 2160,
    "physicalWidth": 1600,
    "physicalHeight": 900,
    "refreshRate": 119.88000,
    "x": 3200,
    "y": 800,
    "activeWorkspace": {
        "id": 3,
        "name": "3"
    },
    "specialWorkspace": {
        "id": 0,
        "name": ""
    },
    "reserved": [0, 0, 0, 0],
    "scale": 1,
    "transform": 0,
    "focused": true,
    "dpmsStatus": true,
    "vrr": false,
    "solitary": "0",
    "solitaryBlockedBy": ["WINDOWED","CANDIDATE"],
    "activelyTearing": false,
    "tearingBlockedBy": ["NOT_TORN","USER","CANDIDATE","HW_CURSOR"],
    "directScanoutTo": "0",
    "directScanoutBlockedBy": ["USER","CANDIDATE"],
    "disabled": false,
    "currentFormat": "XRGB8888",
    "mirrorOf": "none",
    "availableModes": ["3840x2160@60.00Hz","4096x2160@119.88Hz","4096x2160@100.00Hz","4096x2160@59.94Hz","4096x2160@50.00Hz","4096x2160@29.97Hz","4096x2160@25.00Hz","4096x2160@24.00Hz","4096x2160@23.98Hz","3840x2160@119.88Hz","3840x2160@100.00Hz","3840x2160@59.94Hz","3840x2160@50.00Hz","3840x2160@29.97Hz","3840x2160@25.00Hz","3840x2160@23.98Hz","2560x1440@120.00Hz","1920x1080@119.88Hz","1920x1080@100.00Hz","1920x1080@60.00Hz","1920x1080@59.94Hz","1920x1080@50.00Hz","1920x1080@29.97Hz","1920x1080@25.00Hz","1920x1080@23.98Hz","1280x1024@60.02Hz","1152x864@60.00Hz","1280x720@59.94Hz","1280x720@50.00Hz","1024x768@60.00Hz","800x600@60.32Hz","720x576@50.00Hz","720x480@59.94Hz","640x480@59.95Hz","640x480@59.94Hz","640x480@59.93Hz"],
    "colorManagementPreset": "srgb",
    "sdrBrightness": 1,
    "sdrSaturation": 1,
    "sdrMinLuminance": 0.2,
    "sdrMaxLuminance": 80,
    "hardwareCursorsInUse": true
}]
Device: /org/freedesktop/UPower/devices/battery_BAT0
  native-path:          BAT0
  vendor:               LGES
  model:                5B10W51894
  serial:               561
  power supply:         yes
  updated:              Thu 08 Oct 2026 07:40:10 PM CEST (9 seconds ago)
  has history:          yes
  has statistics:       yes
  battery
    present:             yes
    rechargeable:        yes
    state:               fully-charged
    warning-level:       none
    energy:              61.79 Wh
    energy-empty:        0 Wh
    energy-full:         64.42 Wh
    energy-full-design:  94.01 Wh
    voltage-min-design:  11.55 V
    capacity-level:      Normal
    energy-rate:         0 W
    voltage:             12.606 V
    charge-cycles:       101
    percentage:          96%
    capacity:            68.5246%
    technology:          lithium-polymer
    charge-start-threshold:        75%
    charge-end-threshold:          80%
    charge-threshold-supported:    yes
    icon-name:          'battery-full-charged-symbolic'
  History (voltage):
    1791481210	12.606	fully-charged
    1791481180	12.605	fully-charged
    1791481150	12.606	fully-charged

Device: /org/freedesktop/UPower/devices/line_power_AC
  native-path:          AC
  power supply:         yes
  updated:              Thu 08 Oct 2026 09:12:34 AM CEST (37665 seconds ago)
  has history:          no
  performance:
    CpuDriver:	intel_pstate
    PlatformDriver:	platform_profile
    Degraded:   no

* balanced:
    CpuDriver:	intel_pstate
    PlatformDriver:	platform_profile

  power-saver:
    CpuDriver:	intel_pstate
    PlatformDriver:	platform_profile
```

## 1. The full suite, uncached

Stay off the mouse and keyboard for the whole run. The suite takes focus, re-tiles windows, moves the pointer
and empties the clipboard.

```sh
./gradlew --no-daemon --warning-mode all check --continue --rerun-tasks > check.log 2>&1; echo "exit $status"
grep -v '^> Task ' check.log
```

The desktop ran 927 tests, all green. The ones most likely to behave differently here:

- `:wayland`: every test has run with a single output until now. Several aim at `monitors.first()`, which may now
  be the laptop panel at a fractional scale.
- `UpowerLiveTest`: `display` was null on the desktop, which has no battery. Here it should be the battery.
- `PowerProfilesLiveTest`: the desktop has only the placeholder driver, offering power-saver and balanced.

- [x] Exit code, test count, and any failures or warnings:

```text
Commit 8b02eda, 2026-10-08. Exit 1, BUILD FAILED in 3m 47s. 927 tests, 27 failed, 0 skipped, no Gradle warnings.
Hands off the desktop for the whole run. Layout: eDP-1 at 313,1155 (logical 2880x1800, scale 1.333),
HDMI-A-1 at 3200,800 (4096x2160, scale 1, focused).

Expected sizes such as 1920 or 1200 (eDP-1's physical size / 2), Hyprland gave 2880 or 1800 (physical
size / 1.333). The bar itself is 2880 wide on eDP-1 (section 3), so the expectation looks wrong, not the
surface. Not yet checked in code:
  ExclusiveZoneTest: Overlap covers the full output; an explicit exclusiveEdge on a corner anchor
  LayerGeometryTest: all four edges at 0; sized, margined corner anchor; bar at default width
  SurfaceConfigTest: overlay level along the bottom edge; every edge with Overlap
  SurfacePresetTest: lockScreen; desktopBackground

Pointer input never reached the test's surface (a virtual-pointer click at 3208,808 is HDMI-A-1's origin plus 8):
  DragWireTest (all 3), OutputRescaleTest, PointerClockTest, PointerReleaseOrderTest, ProtocolVersionTest
  (wheel scroll), SurfaceLifetimeTest, SurfaceTeardownTest, VirtualPointerClickTest, VirtualPointerCrashTest,
  WindowDragWireTest, PopupGrabWireTest (no grab asked, likely because no press serial arrived)

Two monitors where the test expects one:
  MonitorTest: a monitor the registry removes stayed listed: [Monitor(HDMI-A-1)]

Environment:
  HandBuiltTableTest (both): pkg-config cannot find wlr-protocols, which is not installed here.
  Passed after installing wlr-protocols, so 25 failures are left.

Unclear, and both passed in section 2:
  HyprlandTest.aWorkspaceMovedToAnotherMonitorIsFoundOnIt: timed out after 5000 ms
  TrayWatcherTest "a host never finds the name held with no watcher behind it": got
  CallFailed(UnknownObject, nothing is exported at /StatusNotifierWatcher) instead of null

dunst ran during this run and the hotplug run. It was stopped beforehand, and started again at 15:08:27, most
likely by D-Bus activation from a `busctl --user status org.freedesktop.Notifications` in the prerequisite check.
It is unlikely to have caused a failure: NotificationServerTest and TrayWatcherTest each run a dbus-daemon of
their own (PrivateBus), the notification tests all passed, and the other failures don't touch D-Bus.

Final run, after the cleanup, with the bar and the polkit agent stopped: exit 1, BUILD FAILED in 4m 46s. 928
tests (927 plus the new SurfaceRebuildTest one), 25 failed, 0 skipped, no Gradle warnings. The new test, and
HyprlandTest and TrayWatcherTest, passed. 23 failures are the known expected-size, pointer-input and MonitorTest
ones. 2 are new, both in :tray's TrayLiveTest:
  "the tray carries what the watcher is carrying": the watcher listed no items
  "an unregistration forged by a peer that is not the watcher is not acted on": List is empty
With no watcher running, which this section asks for, the test claims a fresh, empty one, and Steam has to
register with it again. When the tray settles first, it reads no items. Run alone, the first passed and the
second failed again, so it is a race, not the laptop. Steam had last registered with the bar's watcher.
```

If something fails, keep `check.log` and the XML under `*/build/test-results/`. Before anything else, check
whether you touched the desktop during the run.

## 2. The hotplug tests

These add and remove a headless output on the running desktop. Some applications do not survive that, so close
anything you care about first.

```sh
./gradlew --no-daemon check -Pkortex.hotplugTests=true --continue
```

- [x] Result. Note anything else on the desktop that crashed:

```text
Commit 8b02eda plus wlr-protocols installed, 2026-10-08. Exit 1, BUILD FAILED in 3m 43s. 934 tests (927 plus 7
hotplug tests), 24 failed. Nothing else on the desktop crashed, and no headless output was left behind.

Hotplug tests: everything in NamedOutputTest, OutputReleaseWireTest, MultiSurfaceTest, OutputHotplugTest,
SurfaceScaleTest and HotplugCoverageTest passed. 1 failed:
  KortexShellTest "one surface per monitor, created and destroyed as monitors come and go": "the application
  never placed 1 surfaces". It waits for exactly one surface before the hotplug, and there are two monitors.

The other 23 failures are the same expected-size, pointer-input and MonitorTest failures as in section 1.
HyprlandTest.aWorkspaceMovedToAnotherMonitorIsFoundOnIt and TrayWatcherTest "a host never finds the name held
with no watcher behind it" passed this time, so both look flaky rather than broken by the laptop.
```

## 3. The bar, by hand

```sh
./gradlew :bar:run 2>&1 | tee bar.log
```

The bar reserves space at the top of every monitor, so windows shift while it runs. Every time the monitor
list changes, it prints a line like `kortex-bar: monitors [eDP-1, DP-1]` to stderr. When the list is empty
that line is the only sign, because nothing is drawn. A bar that ends shows a crash popup with the reason
instead of disappearing, so a popup or a stack trace in `bar.log` is a finding.

**Two monitors from the start**

- [x] There is one bar on each monitor, and each one fits its own monitor's width.
      `hyprctl layers`: kortex-bar-eDP-1 at 2880x34 (3840 / 1.333), kortex-bar-HDMI-A-1 at 4096x34, and 34
      reserved at the top of each. The tray watcher held two items, Steam and one more.
      dunst still held org.freedesktop.Notifications when the bar started. After `systemctl --user stop
      dunst.service` the bar took the name at once, with no restart.
- [x] In the workspace strip, the pill for the focused monitor's workspace is filled and the one shown on the
      other monitor is tinted. Move focus across and check that they swap.
- [x] Notification popups show on one monitor only, the first one in the list (`notify-send hello`).
      Shown on eDP-1 only, the first monitor in the list.

**A monitor goes away while the bar runs**

- [x] Unplug the external monitor. Its bar goes, and the panel's bar stays and keeps working: the clock ticks,
      clicks work, and the workspace strip shows the workspaces Hyprland moved over.
      bar.log: `monitors [eDP-1]`, then `monitors [eDP-1, HDMI-A-1]` on replug.
- [x] Do it with a tooltip, a tray menu and the right-click menu open on the external monitor, one at a time.
      Each closes with its bar, and nothing on the panel is affected.
      All three: one clean `[eDP-1]` / `[eDP-1, HDMI-A-1]` cycle each, one bar per monitor afterwards, the same
      bar process, no stack trace, and nothing left over on eDP-1.
- [x] If the notification popup was on the monitor that went, the next `notify-send` shows on the one left.
      Popups go to eDP-1, so tested by disabling eDP-1 with HDMI-A-1 on: "one" showed on eDP-1, "two" (sent
      while only HDMI-A-1 was left) showed on HDMI-A-1. After `hyprctl reload` brought eDP-1 back, the list was
      `[HDMI-A-1, eDP-1]`, so the first monitor, and with it the popup's monitor, now depends on the order outputs
      came up rather than on which one is the built-in panel.

**A monitor arrives after the bar started**

- [x] Start the bar with the external monitor unplugged, then plug it in. A bar appears on it with no restart,
      and the `monitors` line lists both.
      Started on `[eDP-1]`; plugging HDMI in logged `[eDP-1, HDMI-A-1]` and a bar appeared on it, same process.
- [x] Unplug and replug it a few times in a row, quickly. Each time it ends up with exactly one bar on it, and
      the panel's bar never flickers or restarts.
      Three fast cycles: the log alternates `[eDP-1]` and `[eDP-1, HDMI-A-1]` cleanly, one kortex-bar layer per
      monitor afterwards, the same bar process throughout, and no stack trace.
- [x] Start the bar on the external monitor alone (lid closed, if your setup turns the panel off), then open
      the lid. The panel gets its bar.
      The lid would not turn the panel off here: there is no Hyprland lid binding, and logind's
      HandleLidSwitchDocked defaults to ignore with two displays connected. So eDP-1 was disabled with
      `hyprctl eval` instead. The bar started on `[HDMI-A-1]`, and `hyprctl reload` gave
      `[HDMI-A-1, eDP-1]` with a 2880x34 bar on the panel, no restart.

**The only monitor goes away**

- [ ] **Differs.** With the external monitor unplugged, turn the panel off: close the lid, or disable it in
      Hyprland. The log shows `monitors []` and the process keeps running.
      Disabled with `hyprctl eval 'hl.monitor({output = "eDP-1", disabled = true})'`; `hyprctl keyword` is
      refused under the Lua config. Hyprland then adds a headless output named FALLBACK, so the log shows
      `monitors [FALLBACK]`, never `monitors []`. The process kept running. Whether kortex should skip FALLBACK
      is open.
- [x] Turn it back on. The bar comes back, and its tray, battery and workspaces are current, not stale.
      `hyprctl reload` brought eDP-1 back; the log shows `monitors [eDP-1]` and the bar is 2880x34 again.
- [x] Turn the screen off with DPMS, the way your idle setup does, then back on. Hyprland keeps the output
      listed, so the bar should not go away or restart.
      `hyprctl eval 'hl.dispatch(hl.dsp.dpms("off"))'` for 10 s: eDP-1 stayed listed with dpmsStatus false,
      the bar's surface stayed, and bar.log gained no line. `hyprctl dispatch dpms off`, which hypridle.conf
      uses, is refused under the Lua config, so hypridle most likely never turns the screen off either.

**Two monitors at different sizes and scales**

The desktop has only ever run one monitor at scale 1, so none of this has been seen yet. Set the two monitors
to different resolutions and scales, ideally one at 1 and one fractional, like 1.5 or 1.25. Record which is
which:

```text
eDP-1: laptop panel, 3840x2400 at scale 1.333 (logical 2880x1800), at 313,1155.
HDMI-A-1: external, 4096x2160 at scale 1, at 3200,800, to the right of the panel and above it.
```

kortex takes each surface's density from `wl_surface.preferred_buffer_scale`, which only carries whole numbers.
It doesn't use the fractional scale protocol. On a monitor at 1.5, expect it to draw at the next whole scale
and Hyprland to scale that down. Text there may look a little softer than in an app that supports fractional
scaling. If you notice that, note it as a finding, but it is expected and not a bug.

- [x] Each bar spans exactly its own monitor's width. On the smaller one, nothing on the right side is cut off
      or pushed out of the bar.
- [x] Both bars are the same height in logical pixels, so the scaled one is taller in physical pixels. The
      text, icons and spacing in it are in proportion, not tiny and not huge.
- [x] Text and icons are sharp at scale 1, and at most slightly soft on the fractional monitor, never blurry
      or pixelated as if drawn at scale 1 and stretched.
- [x] The cursor is the same visual size over both bars.
- [ ] **FAIL.** Clicks and hovers land on what is under the pointer on both monitors, including the bar's very
      edges and corners. An offset that grows towards the right or the bottom means a scale was applied twice or
      not at all.
      A click on one bar also reaches the other bar, at the same position in its own coordinates. Clicking the
      clock in the middle of the eDP-1 bar also clicks the theme switcher on the HDMI-A-1 bar. Theme switching
      itself works.
- [x] Hover a tray icon and the window title until the tooltip opens, on each monitor. It fits its text with
      no clipped edge or extra margin, and it opens just below the pointer without covering it. Measuring at a
      scale other than 1 has only been tested at density 2, never live.
- [x] Right-click the bar for its menu, and right-click a tray item for its menu, on each monitor. Each opens
      next to the pointer, at the right size, and stays on the monitor it was opened on.
- [x] Open the tray menu and a tooltip at the side of the bar that borders the other monitor. They should
      stay on their own monitor rather than spill onto the other one.
- [x] Right-click the bar and choose "Open a window". Move the window from one monitor to the other. It
      redraws at the new monitor's scale, at the same logical size, sharp, and clicks inside it still land
      right. Leave it straddling both monitors and check that it still draws and takes input.
- [x] Change a monitor's scale while the bar runs, through your Hyprland config. The bar on it redraws at the
      new scale with no restart, and the bar on the other monitor is not affected.
- [x] Change a monitor's resolution while the bar runs. The bar on it follows the new width.

**Battery and power**

- [x] The bar shows the laptop's battery.
- [x] Unplug the charger, then plug it back in. The bar follows both changes.
      With the charger attached the bar adds CHG to the battery text; upower then read 71%, charging.
- [ ] A low battery shows in the error colour. Only check this if the battery happens to be low.
- [x] Click the power profile. It cycles through what `powerprofilesctl list` offers, and
      `powerprofilesctl get` agrees after each click. The desktop could never check that a switch actually
      changes anything.
      Cycles through power-saver, balanced and performance, shown as SAVER, BALANCED and PERF.
      `powerprofilesctl get` gave performance afterwards. After one more click the bar showed SAVER and
      `powerprofilesctl get` gave power-saver.

```text
The low battery colour was not checked: the battery stayed at 71% or more.
```

## 4. The polkit agent on two monitors

polkit takes one agent per session, so stop the one this machine runs first. Start that one again afterwards.

```sh
./gradlew :polkit-agent:run
pkexec true
```

- [x] With focus on the panel, the prompt opens on the panel.
- [x] With focus on the external monitor, the prompt opens there.
- [x] The prompt is the same logical size on both monitors, and sharp on each.
- [x] Move focus to the other monitor while the prompt is open. The prompt stays where it is, and typing still
      reaches it.
- [x] Unplug the monitor it opened on while it is open. Note what happens.

```text
Prompts on eDP-1 and HDMI-A-1 each opened on the focused monitor, at the same logical size, sharp on both.
With a prompt open, moving focus to the other monitor, or to a workspace on it, left the prompt where it
was, and a `hyprctl` move-to-workspace on it correctly did nothing. The prompt kept the keyboard focus, so
typing still reached it.
Unplugging HDMI-A-1 with the prompt open on it moved the prompt over to eDP-1, and typing the password there
succeeded. polkit.log shows no error.
```

## 5. Not covered by anything yet

Changing a surface's `monitor` while it is shown rebuilds the surface on the other output.
`SurfaceRebuildTest` only covers a changed namespace, because the desktop has one monitor to move to. The
laptop has a second one, so this is the machine to write that test on. It doesn't need a hotplug.

```text
Written: SurfaceRebuildTest "a changed monitor puts the call on that monitor's output, and its content carries
on". It places a surface on the first monitor hyprctl lists, then asks for the second. hyprctl must then report
the namespace on the second monitor alone, under a new layer surface address, and the shell must hold a new
surface. The content must not have ended, been composed again, or had its effect restarted. With fewer than two
monitors a JUnit assumption skips it, so the desktop reports it as skipped, not as passed.
On the laptop: the whole class passed (10 tests), and the new test passed 5 more times in a row. With the
monitor change ignored on purpose it failed, as it should: "never reported kortex-rebuild-first on HDMI-A-1
alone: [eDP-1]".
```

## Results

Summary for `todo.md`: the date, the commit tested, what passed, and what didn't.

```text
2026-10-08, commit 8b02eda, on a laptop with eDP-1 (3840x2400 at 1.333) and HDMI-A-1 (4096x2160 at 1).

Passed:
  Hotplug: adding and removing an output, per-monitor panels, output release, a second output's scale.
  Bar: one bar per monitor at the right width, unplug and replug (also quickly and with popups open), starting
  on either monitor alone, DPMS, scale and resolution changes, tooltips and menus on both monitors, battery
  and charger, power profile switching that powerprofilesctl confirms.
  Polkit: the prompt opens on the focused monitor, keeps focus, and moves to eDP-1 when HDMI-A-1 goes.
  New: SurfaceRebuildTest moves a surface between monitors (section 5).

Failed:
  A click on one bar also clicks the other bar at the same position (section 3).
  check: 25 failures after installing wlr-protocols, 24 in the hotplug run.
    9 tests expect a size of physical / 2 on eDP-1 where Hyprland uses physical / 1.333.
    13 tests never got pointer input through the compositor.
    MonitorTest and the hotplug KortexShellTest assume a single monitor.
    HyprlandTest and TrayWatcherTest failed once and passed in the hotplug run and the final run.
    The final run: 25 failures, the 23 above plus 2 in :tray's TrayLiveTest. Those read no tray items when
    the tray settles before Steam registers with the watcher the test claims for itself.

Open questions:
  Turning off the only monitor gives `monitors [FALLBACK]`, never `monitors []`. Should kortex skip FALLBACK?
  Notification popups follow the first monitor in the list, which changes with the order outputs come up.
```
