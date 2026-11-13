@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import android.view.Display
import androidx.camera.core.Preview
import com.kashif.cameraK.engine.AspectRatioPreview
import com.kashif.cameraK.engine.PlatformPreviewSource
import com.kashif.cameraK.engine.PreviewAttachment
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.delay

/**
 * The engine's preview leaf. The Preview use case is bound with the engine's other use cases, with no
 * surface until a preview attaches one; the latest open attachment's provider is the Preview's, and
 * its display drives the ViewPort. Closing the latest gives the Preview back to the newest one still
 * open, so a preview in a dialog over a screen's hands the camera back when the dialog goes; closing an
 * older one changes nothing, so an outgoing preview in a crossfade never blanks the incoming one.
 *
 * With no preview attached for [STAND_IN_GRACE_MS] the Preview draws into [DroppingPreviewSurface], so
 * the capture session keeps a repeating request and a photo or an exposure change completes with no
 * preview shown. An ImageAnalysis with an analyzer keeps one too, so while [analysisStreaming] says it
 * streams the stand-in is not used: it would only add a stream. Giving the Preview a surface rebuilds
 * the capture session, so a preview that attaches again within the grace period, as one does when a
 * rotation recreates the Activity, costs one rebuild, not two.
 *
 * [onMain] runs a block on the engine's main dispatcher, at once when already there; every field
 * here is read and written only inside such a block. [aspectRatio] is the configured one, which the
 * ViewPort crops the preview to.
 */
internal class EnginePreviewSource(
    override val aspectRatio: AspectRatio,
    private val onMain: (() -> Unit) -> Unit,
    private val onDisplayAttached: () -> Unit,
    private val launch: (suspend () -> Unit) -> Unit,
    private val analysisStreaming: () -> Boolean = { false },
) : PlatformPreviewSource,
    AspectRatioPreview {
    /** The open attachments, oldest first; the last one's provider is the Preview's. */
    private val attachments = mutableListOf<Attachment>()
    private val current: Attachment? get() = attachments.lastOrNull()
    private var preview: Preview? = null

    /** The Preview whose grace period ended with no preview attached: the stand-in is for it. */
    private var standInFor: Preview? = null

    /** The Preview drawing into the stand-in now; its provider is not readable outside CameraX. */
    private var standInOn: Preview? = null

    /** The Preview use case of the latest bind, or null before the first. */
    val boundPreview: Preview? get() = preview

    /** The display of the current attachment, or null when none is attached. */
    val display: Display? get() = current?.display

    override fun attach(provider: Preview.SurfaceProvider, display: Display?): PreviewAttachment {
        val attachment = Attachment(provider, display)
        onMain {
            attachments += attachment
            standInFor = null
            preview?.give(provider)
            onDisplayAttached()
        }
        return attachment
    }

    /**
     * Uses the stand-in while the grace period has ended with no preview attached and no ImageAnalysis
     * streams, and drops it otherwise. Call it on main when [analysisStreaming] changes: before an
     * analyzer leaves, so the stand-in streams first, and after one comes.
     */
    fun onAnalysisStreamingChanged() {
        val preview = preview
        if (preview == null || preview !== standInFor || current != null) return
        val wanted = !analysisStreaming()
        val using = standInOn === preview
        if (wanted && !using) preview.give(DroppingPreviewSurface)
        if (!wanted && using) preview.give(null)
    }

    private fun Preview.give(provider: Preview.SurfaceProvider?) {
        setSurfaceProvider(provider)
        standInOn = if (provider === DroppingPreviewSurface) this else null
    }

    /**
     * Makes [preview], a use case of the bind in progress, draw into the current attachment. With none,
     * CameraX gives a session that has no other stream one of its own, but counts an ImageAnalysis
     * as one even without an analyzer, when it streams nothing: a bind [withAnalysis] uses the stand-in
     * until an analyzer streams.
     */
    fun bindTo(preview: Preview, withAnalysis: Boolean) {
        this.preview = preview
        standInOn = null
        val attached = current
        if (attached != null) {
            preview.give(attached.provider)
            return
        }
        standInFor = if (withAnalysis) preview else null
        onAnalysisStreamingChanged()
    }

    private inner class Attachment(val provider: Preview.SurfaceProvider, val display: Display?) : PreviewAttachment {
        private val closed = atomic(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            onMain {
                val wasCurrent = current === this
                attachments.remove(this)
                if (!wasCurrent) return@onMain
                val next = current
                if (next != null) {
                    preview?.give(next.provider)
                    onDisplayAttached()
                    return@onMain
                }
                val left = preview
                left?.give(null)
                launch {
                    delay(STAND_IN_GRACE_MS)
                    if (current == null && preview === left) {
                        standInFor = left
                        onAnalysisStreamingChanged()
                    }
                }
            }
        }
    }
}

/** How long the Preview goes without a surface before [DroppingPreviewSurface] takes over. */
internal const val STAND_IN_GRACE_MS = 1_000L
