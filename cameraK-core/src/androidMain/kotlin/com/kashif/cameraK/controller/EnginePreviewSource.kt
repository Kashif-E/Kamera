@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.view.Display
import androidx.camera.core.Preview
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewAttachment
import com.kashif.cameraK.utils.CameraKEngineApi
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The engine's preview leaf. The Preview use case is bound with the engine's other use cases, with no
 * surface until a preview attaches one; the latest attachment's provider is the Preview's, and its
 * display drives the ViewPort. Closing an attachment clears the provider only while it is still the
 * current one, so an outgoing preview in a crossfade never blanks the incoming one.
 *
 * [onMain] runs a block on the engine's main dispatcher, at once when already there; every field
 * here is read and written only inside such a block.
 */
internal class EnginePreviewSource(
    private val onMain: (() -> Unit) -> Unit,
    private val onDisplayAttached: () -> Unit,
) : PlatformPreviewSource {
    private var current: Attachment? = null
    private var preview: Preview? = null

    /** The display of the current attachment, or null when none is attached. */
    val display: Display? get() = current?.display

    override fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment {
        val attachment = Attachment(provider, display)
        onMain {
            current = attachment
            preview?.setSurfaceProvider(provider)
            onDisplayAttached()
        }
        return attachment
    }

    /** Makes [preview], a use case of the bind in progress, draw into the current attachment. */
    fun bindTo(preview: Preview) {
        this.preview = preview
        current?.let { preview.setSurfaceProvider(it.provider) }
    }

    private inner class Attachment(val provider: Preview.SurfaceProvider, val display: Display?) : PreviewAttachment {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            onMain {
                if (current === this) {
                    current = null
                    preview?.setSurfaceProvider(null)
                }
            }
        }
    }
}
