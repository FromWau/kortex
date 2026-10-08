# Testing on the laptop

The development desktop has one monitor, at scale 1, and no battery. This list covers what that machine cannot
show: a second monitor, a scale other than 1, a real battery and a power profiles driver that does something.
Fill in the results as you go and commit this file back.

## Before you start

- [ ] JDK 25 is installed, or Gradle can fetch one.
- [ ] Hyprland is 0.56 or later (`hyprctl version`).
- [ ] `dbus-daemon` is on the `PATH`.
- [ ] GTK is 4.22.5 or later (`pacman -Q gtk4`), which you need before running the hotplug tests.
- [ ] The tray has at least one item in it.
- [ ] Nothing else holds `org.kde.StatusNotifierWatcher`. On ags, run `ags quit`.
- [ ] No clipboard manager or clipboard sync is running, KDE Connect's clipboard plugin included.

Record the machine:

```sh
hyprctl monitors all -j | jq '.[] | {name, width, height, refreshRate, scale}'
upower -d | head -40
powerprofilesctl list
```

```text
(paste here)
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

- [ ] Exit code, test count, and any failures or warnings:

```text

```

If something fails, keep `check.log` and the XML under `*/build/test-results/`. Before anything else, check
whether you touched the desktop during the run.

## 2. The hotplug tests

These add and remove a headless output on the running desktop. Some applications do not survive that, so close
anything you care about first.

```sh
./gradlew --no-daemon check -Pkortex.hotplugTests=true --continue
```

- [ ] Result. Note anything else on the desktop that crashed:

```text

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

- [ ] There is one bar on each monitor, and each one fits its own monitor's width.
- [ ] In the workspace strip, the pill for the focused monitor's workspace is filled and the one shown on the
      other monitor is tinted. Move focus across and check that they swap.
- [ ] Notification popups show on one monitor only, the first one in the list (`notify-send hello`).

**A monitor goes away while the bar runs**

- [ ] Unplug the external monitor. Its bar goes, and the panel's bar stays and keeps working: the clock ticks,
      clicks work, and the workspace strip shows the workspaces Hyprland moved over.
- [ ] Do it with a tooltip, a tray menu and the right-click menu open on the external monitor, one at a time.
      Each closes with its bar, and nothing on the panel is affected.
- [ ] If the notification popup was on the monitor that went, the next `notify-send` shows on the one left.

**A monitor arrives after the bar started**

- [ ] Start the bar with the external monitor unplugged, then plug it in. A bar appears on it with no restart,
      and the `monitors` line lists both.
- [ ] Unplug and replug it a few times in a row, quickly. Each time it ends up with exactly one bar on it, and
      the panel's bar never flickers or restarts.
- [ ] Start the bar on the external monitor alone (lid closed, if your setup turns the panel off), then open
      the lid. The panel gets its bar.

**The only monitor goes away**

- [ ] With the external monitor unplugged, turn the panel off: close the lid, or disable it in Hyprland. The
      log shows `monitors []` and the process keeps running.
- [ ] Turn it back on. The bar comes back, and its tray, battery and workspaces are current, not stale.
- [ ] Turn the screen off with DPMS, the way your idle setup does, then back on. Hyprland keeps the output
      listed, so the bar should not go away or restart.

**Two monitors at different sizes and scales**

The desktop has only ever run one monitor at scale 1, so none of this has been seen yet. Set the two monitors
to different resolutions and scales, ideally one at 1 and one fractional, like 1.5 or 1.25. Record which is
which:

```text
(name, resolution, scale, and which side the external monitor is on)
```

kortex takes each surface's density from `wl_surface.preferred_buffer_scale`, which only carries whole numbers.
It doesn't use the fractional scale protocol. On a monitor at 1.5, expect it to draw at the next whole scale
and Hyprland to scale that down. Text there may look a little softer than in an app that supports fractional
scaling. If you notice that, note it as a finding, but it is expected and not a bug.

- [ ] Each bar spans exactly its own monitor's width. On the smaller one, nothing on the right side is cut off
      or pushed out of the bar.
- [ ] Both bars are the same height in logical pixels, so the scaled one is taller in physical pixels. The
      text, icons and spacing in it are in proportion, not tiny and not huge.
- [ ] Text and icons are sharp at scale 1, and at most slightly soft on the fractional monitor, never blurry
      or pixelated as if drawn at scale 1 and stretched.
- [ ] The cursor is the same visual size over both bars.
- [ ] Clicks and hovers land on what is under the pointer on both monitors, including the bar's very edges
      and corners. An offset that grows towards the right or the bottom means a scale was applied twice or
      not at all.
- [ ] Hover a tray icon and the window title until the tooltip opens, on each monitor. It fits its text with
      no clipped edge or extra margin, and it opens just below the pointer without covering it. Measuring at a
      scale other than 1 has only been tested at density 2, never live.
- [ ] Right-click the bar for its menu, and right-click a tray item for its menu, on each monitor. Each opens
      next to the pointer, at the right size, and stays on the monitor it was opened on.
- [ ] Open the tray menu and a tooltip at the side of the bar that borders the other monitor. They should
      stay on their own monitor rather than spill onto the other one.
- [ ] Right-click the bar and choose "Open a window". Move the window from one monitor to the other. It
      redraws at the new monitor's scale, at the same logical size, sharp, and clicks inside it still land
      right. Leave it straddling both monitors and check that it still draws and takes input.
- [ ] Change a monitor's scale while the bar runs, through your Hyprland config. The bar on it redraws at the
      new scale with no restart, and the bar on the other monitor is not affected.
- [ ] Change a monitor's resolution while the bar runs. The bar on it follows the new width.

**Battery and power**

- [ ] The bar shows the laptop's battery.
- [ ] Unplug the charger, then plug it back in. The bar follows both changes.
- [ ] A low battery shows in the error colour. Only check this if the battery happens to be low.
- [ ] Click the power profile. It cycles through what `powerprofilesctl list` offers, and
      `powerprofilesctl get` agrees after each click. The desktop could never check that a switch actually
      changes anything.

```text
(notes)
```

## 4. The polkit agent on two monitors

polkit takes one agent per session, so stop the one this machine runs first. Start that one again afterwards.

```sh
./gradlew :polkit-agent:run
pkexec true
```

- [ ] With focus on the panel, the prompt opens on the panel.
- [ ] With focus on the external monitor, the prompt opens there.
- [ ] The prompt is the same logical size on both monitors, and sharp on each.
- [ ] Move focus to the other monitor while the prompt is open. The prompt stays where it is, and typing still
      reaches it.
- [ ] Unplug the monitor it opened on while it is open. Note what happens.

```text
(notes)
```

## 5. Not covered by anything yet

Changing a surface's `monitor` while it is shown rebuilds the surface on the other output.
`SurfaceRebuildTest` only covers a changed namespace, because the desktop has one monitor to move to. The
laptop has a second one, so this is the machine to write that test on. It doesn't need a hotplug.

## Results

Summary for `todo.md`: the date, the commit tested, what passed, and what didn't.

```text

```
