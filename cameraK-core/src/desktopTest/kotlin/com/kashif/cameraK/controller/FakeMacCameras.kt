package com.kashif.cameraK.controller

/**
 * The Objective-C runtime of a Mac with [devices] attached, answering the messages macOS camera
 * enumeration sends: `+[AVCaptureDevice devicesWithMediaType:AVMediaTypeVideo]`, the array's
 * `count` and `objectAtIndex:`, each device's `uniqueID`, `localizedName`, `position` and
 * `deviceType`, and `+defaultDeviceWithMediaType:`. Pointers are made-up addresses; a message it
 * does not know answers nil, as a message to nil would.
 */
internal class FakeMacCameras(
    @Volatile var devices: List<Device>,
    @Volatile var defaultId: String? = devices.firstOrNull()?.uniqueId,
) : ObjCRuntime {
    /** One AVCaptureDevice. [position] is AVCaptureDevicePosition: 0 unspecified, 1 back, 2 front. */
    class Device(val uniqueId: String, val name: String, val position: Long, val deviceType: String)

    private val strings = mutableMapOf<Long, String>()
    private var nextString = STRINGS

    override fun getClass(name: String): Long = if (name == "AVCaptureDevice") DEVICE_CLASS else 0

    override fun selector(name: String): Long = SELECTORS.indexOf(name).let { if (it < 0) 0 else SELECTOR + it }

    override fun pointerConstant(library: String, symbol: String): Long = when {
        library != "AVFoundation" -> 0
        symbol == "AVMediaTypeVideo" -> VIDEO
        symbol == "AVMediaTypeAudio" -> AUDIO
        else -> 0
    }

    override fun sendLong(receiver: Long, selector: Long, argument: Long): Long = when {
        receiver == DEVICE_CLASS && selector == sel("devicesWithMediaType:") && argument == VIDEO -> ARRAY
        receiver == DEVICE_CLASS && selector == sel("defaultDeviceWithMediaType:") && argument == VIDEO ->
            devices.indexOfFirst { it.uniqueId == defaultId }.let { if (it < 0) 0 else DEVICE + it }
        receiver == ARRAY && selector == sel("objectAtIndex:") && argument in devices.indices -> DEVICE + argument
        else -> 0
    }

    override fun send(receiver: Long, selector: Long): Long {
        if (receiver == ARRAY && selector == sel("count")) return devices.size.toLong()
        val device = devices.getOrNull((receiver - DEVICE).toInt())?.takeIf { receiver >= DEVICE } ?: return 0
        return when (selector) {
            sel("uniqueID") -> text(device.uniqueId)
            sel("localizedName") -> text(device.name)
            sel("deviceType") -> text(device.deviceType)
            sel("position") -> device.position
            else -> 0
        }
    }

    override fun string(string: Long): String? = synchronized(strings) { strings[string] }

    private fun text(value: String): Long = synchronized(strings) {
        nextString += 1
        strings[nextString] = value
        nextString
    }

    private fun sel(name: String): Long = selector(name)

    companion object {
        const val DEVICE_CLASS = 0x1000L
        const val VIDEO = 0x3000L
        const val AUDIO = 0x3100L
        const val ARRAY = 0x5000L
        const val DEVICE = 0x6000L
        private const val SELECTOR = 0x2000L
        private const val STRINGS = 0x10_0000L
        private val SELECTORS = listOf(
            "authorizationStatusForMediaType:",
            "devicesWithMediaType:",
            "defaultDeviceWithMediaType:",
            "count",
            "objectAtIndex:",
            "uniqueID",
            "localizedName",
            "position",
            "deviceType",
        )

        const val BUILT_IN = "AVCaptureDeviceTypeBuiltInWideAngleCamera"
        const val EXTERNAL = "AVCaptureDeviceTypeExternal"
        const val CONTINUITY = "AVCaptureDeviceTypeContinuityCamera"
    }
}
