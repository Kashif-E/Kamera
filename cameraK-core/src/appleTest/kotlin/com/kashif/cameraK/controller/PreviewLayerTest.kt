@file:OptIn(CameraKEngineApi::class, BetaInteropApi::class, ExperimentalForeignApi::class, InternalCameraKApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.CameraSelection
import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.engine.layOutPreviewLayer
import com.kashif.cameraK.enums.CameraDeviceType
import com.kashif.cameraK.enums.CameraLens
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.objcPtr
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import platform.CoreGraphics.CGRectMake
import platform.QuartzCore.CALayer
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.UIKit.UIInterfaceOrientationPortrait
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session exists from the engine's construction, so a preview composed before the camera starts
 * gets a layer bound to the session that start will run.
 *
 * Binding a layer to the session, and taking the session out of it, configures the session. On main,
 * while the session queue had begun a configuration, the two took AVFoundation's locks in opposite
 * orders and neither returned: an iPhone 14 Pro stalled in Starting with main in
 * `-[AVCaptureSession _addVideoPreviewLayer:]` and the session queue in `beginConfiguration`, both
 * waiting in `_beginConfiguration`, and every later start in the process stalled behind them. Both
 * changes run on the session queue, after the session work already queued there.
 */
class PreviewLayerTest {
    private val driver = AppleEngineDriver()

    @Test
    fun layerCreatedBeforeStartIsBoundToTheSession() = driver.runCase {
        val engine = driver.createEngine()

        val session = driver.session.avSession.objcPtr()
        val layer = engine.preview.createPreviewLayer()
        driver.settle()
        assertEquals(session, layer.session?.objcPtr(), "a layer made before start has another session")

        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(session, layer.session?.objcPtr(), "start replaced the session the layer shows")
        assertEquals(1, driver.sessionsCreated, "sessions the engine created")
        engine.close()
    }

    @Test
    fun aLayerIsBoundToTheSessionOnTheSessionQueue() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()

        val release = holdSessionQueue()
        val layer = try {
            engine.preview.createPreviewLayer().also {
                assertNull(it.session, "the layer was bound to the session while the session queue was busy")
            }
        } finally {
            release()
        }
        driver.settle()

        assertEquals(driver.session.avSession.objcPtr(), layer.session?.objcPtr(), "the session the layer shows")
        engine.close()
    }

    @Test
    fun aCloseTakesTheSessionOutOfTheLayerOnTheSessionQueue() = driver.runCase {
        val engine = driver.createEngine()
        val layer = engine.preview.createPreviewLayer()
        val host = CALayer()
        host.addSublayer(layer)
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        driver.settle()

        // The close reaches the layer from the session queue; main then takes the layer out of its host.
        engine.close()
        awaitSessionQueue()
        val release = holdSessionQueue()
        try {
            NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(MAIN_TURN_SECONDS))
            assertNull(layer.superlayer, "main did not take the closed engine's layer out of its host")
            assertNotNull(layer.session, "the session was taken out of the layer while the session queue was busy")
        } finally {
            release()
        }
        driver.settle()

        assertNull(layer.session, "the closed engine's preview layer still holds its session")
    }

    // Below iOS 17 the layer turns through its connection, which it has only once bound: a layout that
    // came first is applied again.
    @Test
    fun aLayerLaidOutBeforeItIsBoundIsTurnedOnceBound() {
        val driver = AppleEngineDriver(osVersion = 16)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
            driver.settle()

            val release = holdSessionQueue(driver)
            val layer = try {
                engine.preview.createPreviewLayer().also {
                    engine.preview.layOutPreviewLayer(it, PHONE_BOUNDS, UIInterfaceOrientationPortrait)
                }
            } finally {
                release()
            }
            driver.settle()

            assertTrue(layer in driver.boundPreviewTurns, "the layer was not turned once it was bound")
            engine.close()
        }
    }

    // A preview that leaves composition while the engine lives releases its layer: freed on main still
    // bound, the layer would take the session out of itself there.
    @Test
    fun aReleasedLayerIsTakenOutOfTheSessionOnTheSessionQueue() = driver.runCase {
        val engine = driver.createEngine()
        val layer = engine.preview.createPreviewLayer()
        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        driver.settle()

        val release = holdSessionQueue()
        try {
            engine.preview.releasePreviewLayer(layer)
            assertNotNull(layer.session, "the session was taken out of the released layer while the queue was busy")
        } finally {
            release()
        }
        driver.settle()

        assertNull(layer.session, "the released layer still holds the session")
        engine.close()
    }

    // A session shows one layer at a time: binding a second takes the session out of the first. Releasing
    // the first afterwards leaves the second bound.
    @Test
    fun releasingAReplacedLayerLeavesTheNewOneBound() = driver.runCase {
        val engine = driver.createEngine()
        val first = engine.preview.createPreviewLayer()
        val second = engine.preview.createPreviewLayer()
        driver.settle()

        engine.preview.releasePreviewLayer(first)
        driver.settle()

        assertEquals(driver.session.avSession.objcPtr(), second.session?.objcPtr(), "the new layer's session")
        engine.close()
    }

    // From iOS 17 the rotation coordinator is made for the newest layer. A layer made while a start was
    // queued was handed to the coordinator before the queue bound it.
    @Test
    fun theRotationCoordinatorIsMadeOnlyForABoundLayer() {
        val rotation = FakeRotationSources()
        val driver = AppleEngineDriver(osVersion = 17, rotationSources = rotation)
        driver.runCase {
            val engine = driver.createEngine()
            driver.settle()

            val release = holdSessionQueue(driver)
            val start = async { engine.start(backCamera()) }
            val layer = try {
                runCurrent()
                engine.preview.createPreviewLayer()
            } finally {
                release()
            }
            driver.settle()
            assertEquals(CameraKResult.Success(Unit), start.await())

            val made = rotation.made.filter { it.previewLayer != null }
            assertTrue(made.any { it.previewLayer === layer }, "no coordinator for the new layer")
            val unbound = made.filterNot { it.previewLayerBound }.map { it.previewLayer }
            assertEquals(emptyList(), unbound, "coordinators made for a layer not yet bound")
            engine.close()
        }
    }

    // The front camera's preview is mirrored through the layer's connection, which it has only once bound.
    @Test
    fun aFrontLayerIsMirroredOnceBound() = driver.runCase {
        val engine = driver.createEngine()
        driver.settle()
        val front = CameraSelection.ByLens(CameraLens.FRONT, CameraDeviceType.DEFAULT)
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera(front)))
        driver.settle()

        val release = holdSessionQueue()
        val layer = try {
            engine.preview.createPreviewLayer()
        } finally {
            release()
        }
        driver.settle()

        assertTrue((layer to true) in driver.boundPreviewMirrorings, "the front layer was not mirrored once bound")
        engine.close()
    }

    /** Waits, holding main, until [driver]'s session queue has run the work queued before this call. */
    private fun awaitSessionQueue(driver: AppleEngineDriver = this.driver) {
        val reached = dispatch_semaphore_create(0)
        driver.custom.afterQueuedSessionWork { dispatch_semaphore_signal(reached) }
        dispatch_semaphore_wait(reached, DISPATCH_TIME_FOREVER)
    }

    /** Holds [driver]'s session queue, behind the work already queued, until the returned function runs. */
    private fun holdSessionQueue(driver: AppleEngineDriver = this.driver): () -> Unit {
        val held = dispatch_semaphore_create(0)
        driver.custom.afterQueuedSessionWork { dispatch_semaphore_wait(held, DISPATCH_TIME_FOREVER) }
        return { dispatch_semaphore_signal(held) }
    }

    private companion object {
        /** Long enough for main to run what is queued to it. */
        const val MAIN_TURN_SECONDS = 0.2

        val PHONE_BOUNDS = CGRectMake(0.0, 0.0, 390.0, 844.0)
    }
}
