package com.fromwau.kortex.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A [Color] as a theme file writes it: `#AARRGGBB`, or `#RRGGBB` for a colour with no alpha of its own.
 *
 * Alpha leads because that is the order [Color] itself takes an `Int` in, so nothing here reorders
 * channels. It is also matugen's `alpha_hex`, which is the format to ask that generator for.
 */
public object ColorSerializer : KSerializer<Color> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.fromwau.kortex.theme.Color", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Color,
    ) {
        encoder.encodeString("#${value.toArgb().toUInt().toString(HEX).padStart(ARGB_DIGITS, '0')}")
    }

    override fun deserialize(decoder: Decoder): Color {
        val text = decoder.decodeString()
        return colorOrNull(text)
            ?: throw SerializationException("'$text' is not a colour: expected #AARRGGBB or #RRGGBB")
    }
}

/** [text] as a colour, or null where it is not one of the two hex shapes [ColorSerializer] documents. */
private fun colorOrNull(text: String): Color? {
    val digits = text.removePrefix("#")
    if (digits.any { digit -> digit.digitToIntOrNull(HEX) == null }) return null

    val argb = when (digits.length) {
        ARGB_DIGITS -> digits
        RGB_DIGITS -> "$OPAQUE$digits"
        else -> return null
    }

    // Through UInt, because an alpha at or above 0x80 overflows a signed Int and is not a negative colour.
    return argb.toUIntOrNull(HEX)?.let { packed -> Color(packed.toInt()) }
}

private const val HEX = 16
private const val ARGB_DIGITS = 8
private const val RGB_DIGITS = 6
private const val OPAQUE = "ff"
