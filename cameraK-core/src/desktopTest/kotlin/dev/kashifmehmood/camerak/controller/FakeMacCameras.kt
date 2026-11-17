package dev.kashifmehmood.camerak.controller

/**
 * The Objective-C runtime of a Mac with [devices] attached, answering the messages macOS camera
 * enumeration sends: `+[AVCaptureDevice devicesWithMediaType:AVMediaTypeVideo]`, the array's
 * `count` and `objectAtIndex:`, each device's `uniqueID`, `localizedName`, `position` and
 * `deviceType` (each answering `respondsToSelector:`), and `+defaultDeviceWithMediaType:`.
 * Pointers are made-up addresses; a message it does not know answers nil, as a message to nil would.
 */
internal class FakeMacCameras(
    @Volatile var devices: List<Device>,
    @Volatile var defaultId: String? = devices.firstOrNull()?.uniqueId,
) : ObjCRuntime {
    /**
     * One AVCaptureDevice. [position] is AVCaptureDevicePosition: 0 unspecified, 1 back, 2 front.
     * [formats] are its AVCaptureDeviceFormats.
     */
    class Device(
        val uniqueId: String,
        val name: String,
        val position: Long,
        val deviceType: String,
        val formats: List<Format> = emptyList(),
    )

    /** An AVCaptureDeviceFormat: its dimensions and its frame rate ranges, each min to max. */
    class Format(val width: Int, val height: Int, val frameRates: List<Pair<Double, Double>>)

    // The objects a format read hands out: lists, formats, descriptions and frame rate ranges.
    private val objects = mutableMapOf<Long, Any>()
    private var nextObject = OBJECTS

    private class Description(val format: Format)

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
        receiver - DEVICE in devices.indices && selector == sel("respondsToSelector:") ->
            if (argument in DEVICE_SELECTORS.map(::sel)) 1 else 0
        selector == sel("objectAtIndex:") ->
            (objectAt(receiver) as? List<*>)?.getOrNull(argument.toInt())?.let(::handOut) ?: 0
        else -> 0
    }

    override fun send(receiver: Long, selector: Long): Long {
        if (receiver == ARRAY && selector == sel("count")) return devices.size.toLong()
        when (val target = objectAt(receiver)) {
            is List<*> -> return if (selector == sel("count")) target.size.toLong() else 0
            is Format -> return when (selector) {
                sel("formatDescription") -> handOut(Description(target))
                sel("videoSupportedFrameRateRanges") -> handOut(target.frameRates)
                else -> 0
            }
        }
        val device = devices.getOrNull((receiver - DEVICE).toInt())?.takeIf { receiver >= DEVICE } ?: return 0
        return when (selector) {
            sel("uniqueID") -> text(device.uniqueId)
            sel("localizedName") -> text(device.name)
            sel("deviceType") -> text(device.deviceType)
            sel("position") -> device.position
            sel("formats") -> handOut(device.formats)
            else -> 0
        }
    }

    override fun string(string: Long): String? = synchronized(strings) { strings[string] }

    override fun sendDouble(receiver: Long, selector: Long): Double {
        val range = objectAt(receiver) as? Pair<*, *> ?: return 0.0
        return when (selector) {
            sel("minFrameRate") -> range.first as Double
            sel("maxFrameRate") -> range.second as Double
            else -> 0.0
        }
    }

    // CMVideoFormatDescriptionGetDimensions returns CMVideoDimensions, two int32s, in one register.
    override fun callLong(library: String, function: String, argument: Long): Long {
        val description = objectAt(argument) as? Description ?: return 0
        if (library != "CoreMedia" || function != "CMVideoFormatDescriptionGetDimensions") return 0
        return (description.format.height.toLong() shl 32) or description.format.width.toLong()
    }

    private fun objectAt(id: Long): Any? = synchronized(objects) { objects[id] }

    private fun handOut(value: Any?): Long = synchronized(objects) {
        nextObject += 1
        objects[nextObject] = checkNotNull(value)
        nextObject
    }

    // Enumeration asks for nothing.
    override fun sendWithBlock(receiver: Long, selector: Long, argument: Long, block: Long) = Unit

    override fun noOpBlock(): Long = 0

    override fun mainBundlePath(): String? = null

    override fun mainBundleInfo(key: String): String? = null

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
        private const val OBJECTS = 0x20_0000L
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
            "respondsToSelector:",
            "formats",
            "formatDescription",
            "videoSupportedFrameRateRanges",
            "minFrameRate",
            "maxFrameRate",
        )
        private val DEVICE_SELECTORS = listOf("uniqueID", "localizedName", "position", "deviceType")

        const val BUILT_IN = "AVCaptureDeviceTypeBuiltInWideAngleCamera"
        const val EXTERNAL = "AVCaptureDeviceTypeExternal"
        const val CONTINUITY = "AVCaptureDeviceTypeContinuityCamera"
    }
}
