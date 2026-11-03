@file:OptIn(CameraKEngineApi::class)

package com.kashif.cameraK.engine

import com.kashif.cameraK.platform.CameraKPlatformContext
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.utils.CameraKEngineApi

internal actual fun createPlatformEngine(
    config: CameraConfiguration,
    context: CameraKPlatformContext,
    environment: CameraKEnvironment,
): CameraEngine = TODO("S2' red: the legacy bridge")

/** The desktop 1.x session calls. S2' red: the declaration only. */
internal class DesktopLegacySession(
    private val controller: com.kashif.cameraK.controller.CameraController,
    private val io: kotlinx.coroutines.CoroutineDispatcher,
) : LegacySession {
    override suspend fun start(): Unit = TODO("S2' red")

    override suspend fun stop(): Unit = TODO("S2' red")

    override suspend fun close(): Unit = TODO("S2' red")

    override suspend fun <T> onSessionThread(block: () -> T): T = TODO("S2' red")
}
