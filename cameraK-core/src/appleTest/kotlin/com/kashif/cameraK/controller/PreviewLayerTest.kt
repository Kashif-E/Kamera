@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.engine.contract.AppleEngineDriver
import com.kashif.cameraK.result.CameraKResult
import com.kashif.cameraK.utils.CameraKEngineApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The session exists from the engine's construction, so a preview composed before the camera starts
 * gets a layer already bound to the session that start will run.
 */
class PreviewLayerTest {
    private val driver = AppleEngineDriver()

    @Test
    fun layerCreatedBeforeStartIsBoundToTheSession() = driver.runCase {
        val engine = driver.createEngine()

        val layer = engine.preview.createPreviewLayer()
        assertSame(driver.session.avSession, layer.session, "a layer made before start has another session")

        driver.settle()
        assertEquals(CameraKResult.Success(Unit), engine.start(backCamera()))
        assertSame(driver.session.avSession, layer.session, "start replaced the session the layer shows")
        assertEquals(1, driver.sessionsCreated, "sessions the engine created")
        engine.close()
    }
}
