package com.kashif.cameraK.permissions

import android.content.Context
import com.kashif.cameraK.utils.InternalCameraKApi

/**
 * What the app's permission requests answered, kept in the app's own preferences file
 * `com.kashif.cameraK.permissions`: `asked.<name>` once a request for the Android permission `<name>`
 * returned, and `permanentlyDenied.<name>` once one was inferred permanent. `:cameraK`'s request
 * writes it, because only an Activity can say whether to show a rationale; core's checker reads it.
 */
@InternalCameraKApi
public class PermissionRequestRecord(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Records one request's result for [permission]. A denial after which the activity shows no
     * rationale is permanent only if an earlier request was answered too: a first request the user
     * dismissed also leaves no rationale.
     */
    public fun recordResult(permission: String, granted: Boolean, shouldShowRationale: Boolean) {
        val askedBefore = wasAsked(permission)
        val editor = preferences.edit().putBoolean(ASKED + permission, true)
        when {
            granted || shouldShowRationale -> editor.remove(PERMANENTLY_DENIED + permission)
            askedBefore -> editor.putBoolean(PERMANENTLY_DENIED + permission, true)
        }
        editor.apply()
    }

    internal fun wasAsked(permission: String): Boolean = preferences.getBoolean(ASKED + permission, false)

    internal fun isPermanentlyDenied(permission: String): Boolean =
        preferences.getBoolean(PERMANENTLY_DENIED + permission, false)

    internal fun clearPermanentDenial(permission: String) {
        if (isPermanentlyDenied(permission)) preferences.edit().remove(PERMANENTLY_DENIED + permission).apply()
    }

    private companion object {
        const val FILE = "com.kashif.cameraK.permissions"
        const val ASKED = "asked."
        const val PERMANENTLY_DENIED = "permanentlyDenied."
    }
}
