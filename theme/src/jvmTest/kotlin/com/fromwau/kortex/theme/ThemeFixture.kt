package com.fromwau.kortex.theme

/**
 * Every role the schema requires, which is every role material3's `ColorScheme` has.
 *
 * One list rather than one per test: a role added or renamed in the schema has to land here, and a second
 * copy is a test that fails for a reason nobody will connect to the schema it was written against.
 */
internal val ROLES = listOf(
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
    "surfaceTint", "inverseSurface", "inverseOnSurface",
    "error", "onError", "errorContainer", "onErrorContainer",
    "outline", "outlineVariant", "scrim", "surfaceBright", "surfaceDim",
    "surfaceContainer", "surfaceContainerHigh", "surfaceContainerHighest",
    "surfaceContainerLow", "surfaceContainerLowest",
    "primaryFixed", "primaryFixedDim", "onPrimaryFixed", "onPrimaryFixedVariant",
    "secondaryFixed", "secondaryFixedDim", "onSecondaryFixed", "onSecondaryFixedVariant",
    "tertiaryFixed", "tertiaryFixedDim", "onTertiaryFixed", "onTertiaryFixedVariant",
)

/**
 * One scheme's worth of a theme file: [filler] for every role, with [named] overriding individual ones.
 *
 * A test that cares about a particular role names it rather than writing out 48 lines, and the colours it
 * does not name are still real colours, so a parse failure is about the role the test is pinning.
 */
internal fun roles(filler: String, named: Map<String, String> = emptyMap()): String =
    ROLES.joinToString(",\n") { role -> """    "$role": "${named[role] ?: filler}"""" }
