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
- **JDK 25**, which is what the Gradle toolchain asks for.
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
| `:tray` | the system tray: `StatusNotifierItem` and `DBusMenu` | `:dbus` |
| `:notification` | kortex as the notification server, not a client of one | `:dbus` |
| `:watch` | a file's text, again whenever it changes | `kern:dirs` |
| `:bar` | a runnable demo of most of the toolkit at once | `:wayland` |

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
        .getOrElse { return }

    server.notifications.collect { posted -> println(posted.map { it.summary }) }
}
```

Plenty of what a bar shows lives in a file rather than on a bus, so `:watch` hands over a file's text and
hands it over again when it changes:

```kotlin
val memory: StateFlow<MemInfo?> = fileWatcher(Path("/proc/meminfo"), every = 2.seconds)
    .map { read -> read.getOrNull()?.let(::parseMemInfo) }
    .stateIn(scope, SharingStarted.WhileSubscribed(), null)
```

The first value is always the file as it stands, and after that only changes arrive, so you need no
`distinctUntilChanged()` of your own. `fileWatcher(path)` without an interval waits for the operating system
instead of reading on a tick, which is what you want for a config file in `$HOME`. It is **not** what works
for the example above: procfs and sysfs make a file's contents up as it is read, so there is no write for
the kernel to report, and `inotify(7)` names both as unmonitorable. That overload says so, with
`WatchError.Unwatchable` naming the filesystem, rather than leaving you a widget that looks fine and never
updates.

In a composition, `collectAsState()` is the bridge, and kortex ships no helper for it.

## Building and running

Use the wrapper.

```sh
./gradlew assemble       # compile everything
./gradlew :bar:run       # the demo bar, on every monitor
./gradlew :bar:packageDeb
```

Nothing is published yet. Publishing waits until development settles, so for now a consumer builds from
source.

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
- **`:tray`'s tests need a tray with at least one item in it**, since what they read is whatever your
  session is carrying.

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
