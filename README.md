# kortex

Compose Multiplatform for the Wayland desktop shell. Bars, docks, on-screen displays, wallpapers, lock
screens, windows, dialogs and menus are composables, and the desktop state a shell wants to draw on them
arrives as flows.

A surface places itself from the parameters you give it, and a change to any of them reaches a surface
already on screen. There is no helper binary and no socket of kortex's own: the toolkit speaks the Wayland
protocols directly through the JDK's foreign function interface, and the providers speak D-Bus directly over
a Unix socket.

```kotlin
fun main() {
    kortexApplication {
        val monitors by rememberMonitors()
        for (monitor in monitors) key(monitor) {
            Bar(monitor = monitor, thickness = 40.dp, namespace = "my-bar") {
                BasicText("hello from ${monitor.name}")
            }
        }
    }.onError { error ->
        System.err.println("kortex: $error")
        exitProcess(1)
    }
}
```

Keying by the monitor is what keeps each bar with its own monitor as others are plugged in and unplugged.
Compose's runtime, ui and foundation come with `:wayland`; material3 is yours to add if you want it.

## What it needs

- **A compositor with `zwlr_layer_shell_v1`**: wlroots compositors, Hyprland and sway among them, and KWin.
  kortex refuses to start without it and names it, rather than running as half a toolkit whose bars and
  wallpapers silently never appear. Development happens against Hyprland.
- **JDK 25**, which is what the Gradle toolchain asks for. A consumer build needs its own toolchain
  repository to fetch one, since a settings plugin does not reach a build that merely includes kortex:
  `id("org.gradle.toolchains.foojay-resolver-convention")` in your `settings.gradle.kts`, or a JDK 25
  already on the machine. Without either, Gradle auto-provisions one and warns, and that warning becomes
  an error in Gradle 10.
- **`libwayland-client.so.0`, `libwayland-cursor.so.0` and `libxkbcommon.so.0`** at runtime, and
  `--enable-native-access=ALL-UNNAMED` on the JVM that runs your shell, because the bindings are foreign
  calls.
- **Linux on Wayland.** JVM only, by design.

Library versions all live in `gradle/libs.versions.toml`, which is the only place they are written down.

## The modules

| module | what it is | depends on |
| --- | --- | --- |
| `:wayland` | the toolkit: surfaces, input, the clipboard, drag and drop, monitors | `:compose` |
| `:compose` | hosting Compose content on a surface, with no Wayland in it | Compose runtime, ui, foundation |
| `:dbus` | a D-Bus client and server, public so you can write a provider of your own | nothing of kortex's |
| `:tray` | the system tray, as the host that draws it and the registry applications register with | `:dbus` |
| `:notification` | kortex as the notification server, not a client of one | `:dbus` |
| `:watch` | a file's text, again whenever it changes | `kern:dirs` |
| `:hyprland` | Hyprland's monitors and their workspaces, the focused window, the submap and the keyboard layout, and its dispatchers, over its own sockets | nothing of kortex's |
| `:shell` | a bash script's output and exit code, for whatever no provider covers | nothing of kortex's |
| `:icons` | a provider's icon as something Compose can draw | `:tray`, `:notification`, Compose |
| `:theme` | a watched JSON file as a Material `ColorScheme`, so a bar retints itself | `:watch`, Compose, material3 |
| `:bar` | a bar you could run, and a demo of the toolkit's other surfaces behind a right click | most of the above |

Twelve surfaces are composables: `Bar`, `Panel`, `Dock`, `DesktopBackground`, `LockScreen`, `Osd`, `AppMenu`
and `ContextMenu`, the `LayerSurface` they are all presets of, and `Window`, `Dialog` and `Popup` over
xdg-shell. Each takes its content with the surface itself as the receiver, so content can read the size the
compositor gave it and close it.

## The desktop's own state

A provider gives data and no UI. A widget is where a toolkit starts having opinions about what a bar should
look like, and kortex has none, so a tray icon arrives as a width, a height and ARGB bytes and stays that
way. Each provider is one `StateFlow` carrying a `Result`, where the error says why there is no data, and
nothing runs while nobody is collecting.

```kotlin
suspend fun printTray(scope: CoroutineScope) {
    val connection = DBusConnection.session().getOrElse { return }

    Tray(connection, scope).items.collect { outcome ->
        val items = outcome.getOrNull() ?: return@collect
        items.forEach { item -> println("${item.id}: ${item.title} (${item.status})") }
    }
}
```

Becoming the notification server is the one thing that succeeds or fails outright, because only one
connection on a bus may hold the name:

```kotlin
suspend fun printNotifications(connection: DBusConnection) {
    val server = NotificationServer
        .start(connection, ServerInformation(name = "my-shell", vendor = "me", version = "0.1.0"))
        .getOrElse { error ->
            // AlreadyServed names the process already holding it, which is the difference between
            // "it did not start" and "stop dunst first".
            System.err.println("my-shell: $error")
            return
        }

    server.notifications.collect { posted -> println(posted.map { it.summary }) }
}
```

An icon arrives as a theme name, or as pixels, or both, and `:icons` turns whichever into something to
draw, resolving the name through the desktop's own icon theme:

```kotlin
Row {
    BasicText(item.label)
    Icon(item.icon, null)   // a TrayIcon, a MenuIcon or a NotificationImage
}
```

It does not tint, unlike material3's `Icon` of the same name, because tinting is right for a glyph and
wrong for an application's artwork, and it holds its space when an icon resolves to nothing so a tray does
not reflow as icons arrive.

Plenty of what a bar shows lives in a file rather than on a bus, so `:watch` hands over a file's text and
hands it over again when it changes:

```kotlin
import kotlinx.io.files.Path   // kern's Path, not java.nio's

val memory: StateFlow<MemInfo?> = Path("/proc/meminfo").readTextEvery(2.seconds)
    .map { read -> read.getOrNull()?.let(::parseMemInfo) }
    .stateIn(scope, SharingStarted.WhileSubscribed(), null)
```

The first value is always the file as it stands, and after that only changes arrive, so you need no
`distinctUntilChanged()` of your own. Note what that means for a rate: `every` is not the gap between
values, because a file that stops changing emits nothing, so divide a counter's delta by the gap you
measured rather than by the interval.

`watchText()` is the other half of the pair. It waits for the operating system instead of reading on a
tick, which is what you want for a config file in `$HOME`. It is **not** what works for the example above:
procfs and sysfs make a file's contents up as it is read, so there is no write for the kernel to report,
and `inotify(7)` names both as unmonitorable. It says so, with `WatchError.Unwatchable` naming the
filesystem, rather than leaving you a widget that looks fine and never updates.

Finding the file is yours. A sysfs reading rarely has a path you can write down: `/sys/class/hwmon/hwmonN`
is numbered in probe order, and which `tempN_input` you want is decided by the `tempN_label` beside it.
List and choose with `kern:dirs`, then watch what you found.

In a composition, `collectAsState()` is the bridge, and kortex ships no helper for it.

## Building and running

Use the wrapper.

```sh
./gradlew assemble       # compile everything
./gradlew :bar:run       # the demo bar, on every monitor
./gradlew :bar:packageDeb
```

Nothing is published yet. Publishing waits until development settles, so for now a consumer builds from
source: `includeBuild("path/to/kortex")` in your `settings.gradle.kts` and then the ordinary coordinate,
`implementation("com.fromwau.kortex:wayland:0.1.0")`, which Gradle substitutes from the included build.

Your repositories need `mavenCentral()`, Google's Maven for the androidx artifacts Compose pulls in, and
`maven("https://maven.frommhund.xyz/releases")` for kern. Without the second, the failure names an androidx
artifact rather than anything of kortex's, which is a confusing first five minutes.

Two probes print live desktop state and wait for you rather than driving themselves, which is why no test
task can run one:

```sh
./gradlew :tray:probe -Pprobe=com.fromwau.kortex.tray.LiveTrayProbeKt
./gradlew :tray:probe -Pprobe=com.fromwau.kortex.tray.LiveMenuProbeKt
```

## The tests

Nothing is faked at the protocol boundary. Where a test needs a compositor it uses the one you are running,
and where it needs a bus it uses your session bus. A fake bus was written early on and thrown away: it
reimplemented the protocol, so it agreed with whatever kortex had got wrong about it, which is the one thing
these tests exist to catch.

That makes the suite a demanding guest, and worth knowing about before you run it:

- **It takes the desktop.** Several tests take focus, re-tile your open windows and drive the pointer, so
  they want a session you are not using.
- **It empties the clipboard.** One test ends by clearing it, rather than restoring what was there.
- **A clipboard manager or sync service will fail the clipboard tests**, intermittently and for reasons
  that look like kortex's fault. They take the selection between kortex setting it and `wl-paste` reading
  it, which no amount of waiting on this side prevents. KDE Connect's clipboard plugin copying from
  another machine does it, so stop that kind of thing, or expect `ClipboardFocusTest` to fail now and then.
- **`:hyprland`'s tests need Hyprland 0.56 or later**, whose `dispatch` takes Lua. One of them switches to
  workspace 77 or the first free one above it, renames it and switches back to the window that had focus.
- **`:tray`'s tests need a tray with at least one item in it**, since what they read is whatever your
  session is carrying.
- **`:tray`'s tests also need `org.kde.StatusNotifierWatcher` free**, because they become the registry
  themselves. Stop whatever holds it first, which on a session running ags is `ags quit`. Applications
  already in the tray re-register with the test's own watcher within a second or so, so you do not lose
  the items by doing this; you get them back when you start your bar again.

```sh
./gradlew check                                     # everything that needs no special arrangement
./gradlew check -Pkortex.notificationTests=true     # also the notification server, see below
./gradlew check -Pkortex.hotplugTests=true          # also output hotplug, see below
```

Two groups are gated behind a property each, because opting into one is no reason to opt into the other.
The notification server tests take `org.freedesktop.Notifications`, which only one connection may hold, so
whatever holds it has to stop first; the name is D-Bus activatable, so the daemon comes back on its own the
moment kortex releases it. The hotplug tests add and remove a real output on your running desktop, which
some applications do not survive.

## Status

Pre-release. Every library module is `explicitApi()` and its public surface is documented, but that surface
is not stable and nothing is published.

`todo.md` is the working record: what is built, what is deliberately not being built, and every finding from
the audits and reviews with the evidence behind it.

## Licence

Apache-2.0. See `LICENSE`.
