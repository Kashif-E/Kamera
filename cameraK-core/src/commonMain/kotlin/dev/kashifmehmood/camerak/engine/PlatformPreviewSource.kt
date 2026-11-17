package dev.kashifmehmood.camerak.engine

import dev.kashifmehmood.camerak.utils.CameraKEngineApi

/**
 * Where a platform's preview comes from, in the platform's own shape. Used only by camerak's
 * preview composables: an Android surface provider attachment, an iOS preview layer, desktop frames.
 */
@CameraKEngineApi
public expect interface PlatformPreviewSource
