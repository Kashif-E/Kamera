package com.kashif.cameraK.permissions

import android.content.Context
import com.kashif.cameraK.utils.InternalCameraKApi

@InternalCameraKApi
public class PermissionRequestRecord(context: Context) {
    public fun recordResult(permission: String, granted: Boolean, shouldShowRationale: Boolean): Unit = TODO()
}
