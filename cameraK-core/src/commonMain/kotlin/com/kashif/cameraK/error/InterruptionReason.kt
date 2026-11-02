package com.kashif.cameraK.error

import kotlin.jvm.JvmInline

/**
 * Open: new values can be added in a minor release, so a `when` over it needs an `else` branch.
 * Why the platform paused a session it still holds; the session resumes on its own.
 */
@JvmInline
public value class InterruptionReason private constructor(public val name: String) {
    override fun toString(): String = TODO("S1' stub")

    public companion object {
        public val APP_IN_BACKGROUND: InterruptionReason = InterruptionReason("APP_IN_BACKGROUND")
        public val MULTITASKING: InterruptionReason = InterruptionReason("MULTITASKING")
        public val IN_USE_BY_ANOTHER_CLIENT: InterruptionReason = InterruptionReason("IN_USE_BY_ANOTHER_CLIENT")
        public val AUDIO_IN_USE: InterruptionReason = InterruptionReason("AUDIO_IN_USE")
        public val SYSTEM_PRESSURE: InterruptionReason = InterruptionReason("SYSTEM_PRESSURE")
        public val MEDIA_SERVICES_RESET: InterruptionReason = InterruptionReason("MEDIA_SERVICES_RESET")
        public val OTHER: InterruptionReason = InterruptionReason("OTHER")
    }
}
