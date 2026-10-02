package com.fromwau.kortex.notification

import com.fromwau.kortex.dbus.DBusValue
import com.fromwau.kortex.dbus.asBoolean
import com.fromwau.kortex.dbus.asByte
import com.fromwau.kortex.dbus.asBytes
import com.fromwau.kortex.dbus.asDictionary
import com.fromwau.kortex.dbus.asFields
import com.fromwau.kortex.dbus.asInt32
import com.fromwau.kortex.dbus.asItems
import com.fromwau.kortex.dbus.asText
import com.fromwau.kortex.dbus.flag
import com.fromwau.kortex.dbus.text
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** How much an application says its notification matters. */
public enum class Urgency(internal val wireValue: Int) {
    Low(0),
    Normal(1),
    Critical(2),
    ;

    internal companion object {
        /** [Normal] for anything unrecognised, which is also what an application that sends no hint gets. */
        fun fromWire(raw: Int?): Urgency = entries.firstOrNull { it.wireValue == raw } ?: Normal
    }
}

/**
 * How long an application wants its notification shown.
 *
 * A type rather than the wire's int, where -1 and 0 are two different sentinels among ordinary
 * millisecond counts and nothing but a comment tells them apart.
 */
public sealed interface Expiry {
    /** The application has no opinion, so the decision is the one showing it. */
    public data object ServerDefault : Expiry

    /** It stays until something closes it. */
    public data object Never : Expiry

    public data class After(public val duration: Duration) : Expiry
}

/**
 * The `expire_timeout` argument as an [Expiry].
 *
 * A top-level function rather than a companion, because a sealed interface cannot have an internal one and
 * this is not something a caller of the module ever needs.
 */
internal fun expiryFromWire(milliseconds: Int): Expiry = when {
    milliseconds == EXPIRY_SERVER_DECIDES -> Expiry.ServerDefault
    milliseconds == EXPIRY_NEVER -> Expiry.Never
    // A negative that is not the sentinel is nonsense, and reading it as no opinion loses nothing.
    milliseconds < 0 -> Expiry.ServerDefault
    else -> Expiry.After(milliseconds.milliseconds)
}

private const val EXPIRY_SERVER_DECIDES = -1
private const val EXPIRY_NEVER = 0

/** One thing a user can do with a notification, beyond dismissing it. */
public data class NotificationAction(
    /** What goes back in `ActionInvoked`; `"default"` is the one a click on the body means. */
    public val key: String,
    /** What to draw, or the name of an icon where the server declared `action-icons`. */
    public val label: String,
)

/**
 * Pixels an application sent with its notification, as they arrive.
 *
 * Raw and unencoded, unlike a menu entry's icon: [channels] is 3 or 4, and [rowStride] is the bytes per
 * row, which is not always [width] times [channels] because rows are padded.
 *
 * Not the same bytes as a tray icon, which is the other place a provider here hands over pixels: that one
 * is always four channels of `ARGB` in network byte order, packed with no stride. One unpacker cannot read
 * both, and reusing the wrong one draws a real picture in rotated colours rather than failing.
 */
public class NotificationImage(
    public val width: Int,
    public val height: Int,
    public val rowStride: Int,
    public val hasAlpha: Boolean,
    public val bitsPerSample: Int,
    public val channels: Int,
    public val pixels: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is NotificationImage &&
        width == other.width &&
        height == other.height &&
        rowStride == other.rowStride &&
        hasAlpha == other.hasAlpha &&
        bitsPerSample == other.bitsPerSample &&
        channels == other.channels &&
        pixels.contentEquals(other.pixels)

    override fun hashCode(): Int = listOf(width, height, rowStride, hasAlpha, bitsPerSample, channels)
        .fold(0) { acc, field -> acc * 31 + field.hashCode() } * 31 + pixels.contentHashCode()

    override fun toString(): String = "NotificationImage(${width}x$height, ${pixels.size} bytes)"
}

/** Where on screen an application asked its notification to point, where it asked at all. */
public data class ScreenHint(public val x: Int, public val y: Int)

/** One notification an application has posted. */
public data class Notification(
    public val id: UInt,
    /**
     * Which posting of [id] this is, counted from 1 and up by one for each replacement.
     *
     * A replacement reuses the id, and an application is free to replace a notification with content
     * identical to what is already there: a progress notification that has not moved yet does exactly
     * that. Two such postings are otherwise the same value, which [NotificationServer.notifications] would
     * conflate, so this is what tells a caller animating or timing one that the application has posted
     * again.
     */
    public val revision: UInt,
    public val appName: String,
    /**
     * A theme icon name or a file path; empty where the application sent none, which is common.
     *
     * A caller drawing an icon has to consider [imagePath] and [image] too, and cannot prefer this. Read
     * off the wire: `notify-send --icon=dialog-information` leaves this empty and puts the name in the
     * `image-path` hint, so a caller reading only this shows nothing for most of what gets sent.
     */
    public val appIcon: String,
    public val summary: String,
    /** May carry the small subset of markup the specification allows, which a caller decides about. */
    public val body: String,
    public val actions: List<NotificationAction>,
    public val urgency: Urgency,
    public val expiry: Expiry,
    public val category: String?,
    public val desktopEntry: String?,
    /** The application asked for this not to be kept in a history. */
    public val isTransient: Boolean,
    /** The application asked for this to stay after an action is invoked rather than be closed. */
    public val isResident: Boolean,
    public val image: NotificationImage?,
    public val imagePath: String?,
    public val soundFile: String?,
    public val soundName: String?,
    public val suppressSound: Boolean,
    /** Each action's key names an icon rather than being a label. */
    public val actionIcons: Boolean,
    public val screenHint: ScreenHint?,
    /**
     * Every hint exactly as it arrived, the ones above included.
     *
     * Kept because applications send their own: dunst's own `x-dunst-stack-tag` and the several spellings
     * of a synchronous-replace tag are all hints no specification lists, and a caller that wants to honour
     * one has nowhere else to read it.
     */
    public val hints: Map<String, DBusValue>,
)

/** Why a notification stopped being shown, under the numbers the signal carries. */
public enum class CloseReason(internal val wireValue: UInt) {
    Expired(1u),
    Dismissed(2u),

    /** The application asked for it to go, through `CloseNotification`. */
    Withdrawn(3u),

    Undefined(4u),
}

/** Builds a notification from one `Notify` call, with [id] and [revision] already decided. */
internal fun notificationFrom(
    id: UInt,
    body: List<DBusValue>,
    revision: UInt = FIRST_REVISION,
): Notification? {
    if (body.size != NOTIFY_ARGUMENTS) return null

    val hints = body[6].asDictionary.orEmpty()

    return Notification(
        id = id,
        revision = revision,
        appName = body[0].asText.orEmpty(),
        appIcon = body[2].asText.orEmpty(),
        summary = body[3].asText.orEmpty(),
        body = body[4].asText.orEmpty(),
        actions = actionsIn(body[5]),
        urgency = Urgency.fromWire(hints[URGENCY]?.asByte?.toInt()),
        expiry = expiryFromWire(body[7].asInt32 ?: EXPIRY_SERVER_DECIDES),
        category = hints.present(CATEGORY),
        desktopEntry = hints.present(DESKTOP_ENTRY),
        isTransient = hints.flag(TRANSIENT),
        isResident = hints.flag(RESIDENT),
        // image-data supersedes the icon_data of specification 1.0, and an application may still send either.
        image = imageIn(hints[IMAGE_DATA] ?: hints[ICON_DATA]),
        imagePath = hints.present(IMAGE_PATH),
        soundFile = hints.present(SOUND_FILE),
        soundName = hints.present(SOUND_NAME),
        suppressSound = hints.flag(SUPPRESS_SOUND),
        actionIcons = hints.flag(ACTION_ICONS),
        screenHint = screenHintIn(hints),
        hints = hints,
    )
}

/** Alternating key and label, flat, which is how the wire carries pairs it has no type for. */
private fun actionsIn(value: DBusValue): List<NotificationAction> =
    value
        .asItems
        ?.mapNotNull { it.asText }
        ?.chunked(2)
        ?.mapNotNull { pair -> pair.takeIf { it.size == 2 }?.let { NotificationAction(it[0], it[1]) } }
        .orEmpty()

private fun imageIn(value: DBusValue?): NotificationImage? {
    val fields = value?.asFields?.takeIf { it.size == IMAGE_FIELDS } ?: return null
    return NotificationImage(
        width = fields[0].asInt32 ?: return null,
        height = fields[1].asInt32 ?: return null,
        rowStride = fields[2].asInt32 ?: return null,
        hasAlpha = fields[3].asBoolean ?: return null,
        bitsPerSample = fields[4].asInt32 ?: return null,
        channels = fields[5].asInt32 ?: return null,
        pixels = fields[6].asBytes ?: return null,
    )
}

private fun screenHintIn(hints: Map<String, DBusValue>): ScreenHint? {
    val x = hints[X]?.asInt32 ?: return null
    val y = hints[Y]?.asInt32 ?: return null
    return ScreenHint(x, y)
}

/** A hint an application sent as an empty string is one it did not send, for every hint here. */
private fun Map<String, DBusValue>.present(key: String): String? = text(key)?.ifEmpty { null }

/** What a notification nothing has replaced yet is on. */
internal const val FIRST_REVISION: UInt = 1u

/** What `Notify` takes, in order. A call of any other width is not one. */
private const val NOTIFY_ARGUMENTS = 8

/** Width, height, rowstride, has-alpha, bits per sample, channels, pixels. */
private const val IMAGE_FIELDS = 7

private const val URGENCY = "urgency"
private const val CATEGORY = "category"
private const val DESKTOP_ENTRY = "desktop-entry"
private const val TRANSIENT = "transient"
private const val RESIDENT = "resident"
private const val IMAGE_DATA = "image-data"
private const val ICON_DATA = "icon_data"
private const val IMAGE_PATH = "image-path"
private const val SOUND_FILE = "sound-file"
private const val SOUND_NAME = "sound-name"
private const val SUPPRESS_SOUND = "suppress-sound"
private const val ACTION_ICONS = "action-icons"
private const val X = "x"
private const val Y = "y"
