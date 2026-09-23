package com.fromwau.kortex.wayland

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.fromwau.kortex.compose.KortexPlatform
import com.fromwau.kortex.compose.KortexScene
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Surface

/**
 * Runs [block] on a scene of [size] and a raster to render it into; the scene and the raster close after.
 *
 * The scene's work stays on the calling thread, as a shell's stays on its loop thread. A scene handed a
 * dispatcher of its own instead runs content's effects there while the caller renders here, and a
 * `BasicTextField(TextFieldState)` put through that fails as multithreaded access to `SnapshotStateObserver`.
 */
internal fun onScene(
    size: IntSize,
    platform: KortexPlatform = KortexPlatform.None,
    block: (scene: KortexScene, surface: Surface) -> Unit,
) {
    Surface.makeRasterN32Premul(size.width, size.height).use { surface ->
        KortexScene(
            size = size,
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            // Unconfined needs no dispatch, so Compose registers no snapshot pump for it and FrameRecomposer
            // sends the apply notifications itself, inside the render this thread drives.
            frameContext = Dispatchers.Unconfined,
            onInvalidate = {},
            platform = platform,
        ).use { scene -> block(scene, surface) }
    }
}
