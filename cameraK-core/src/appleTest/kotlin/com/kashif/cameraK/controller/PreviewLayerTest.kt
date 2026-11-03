@file:OptIn(CameraKEngineApi::class, BetaInteropApi::class, ExperimentalForeignApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.objcPtr
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The session exists from the engine's construction, so a preview composed before the camera starts
 * gets a layer already bound to the session that start will run.
 */
class PreviewLayerTest {
    private val driver = AppleEngineDriver()

    @Test
    fun layerCreatedBeforeStartIsBoundToTheSession() = driver.runCase {
        val engine = driver.createEngine()

        val session = driver.session.avSession.objcPtr()
        val layer = engine.preview.createPreviewLayer()
        assertEquals(session, layer.session?.objcPtr(), "a layer made before start has another session")

        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertEquals(session, layer.session?.objcPtr(), "start replaced the session the layer shows")
        assertEquals(1, driver.sessionsCreated, "sessions the engine created")
        engine.close()
    }
}
