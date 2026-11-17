package dev.kashifmehmood.camerak.permissions

import dev.kashifmehmood.camerak.platform.CameraKPlatformContext

/**
 * The platform's own permission checker. Its one caller is the production constructor's
 * environment; a holder built through the engine constructor uses the checker its environment
 * names, or none.
 */
internal expect fun platformPermissionChecker(context: CameraKPlatformContext): CameraKPermissionChecker
