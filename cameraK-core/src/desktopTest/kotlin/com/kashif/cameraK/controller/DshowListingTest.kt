package com.kashif.cameraK.controller

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * FFmpeg 7.1's dshow device list returns the audio category's result, and avdevice frees the list on
 * an error: on a Windows machine with no audio capture device it listed no camera at all (the
 * windows-2025 runner). CameraK then reads the cameras from the older list_devices query's log.
 */
class DshowListingTest {
    // FFmpeg's log as the query writes it: each line prefixed by its context, written in pieces.
    private val log =
        "[dshow @ 000001c2] \"Unity Video Capture\" (video)\n" +
            "[dshow @ 000001c2]   Alternative name \"@device_sw_{860BB310-5D01-11D0}\\{5C2CD55C-92AD-4999}\"\n" +
            "[dshow @ 000001c2] \"HD Pro Webcam C920\" (video)\n" +
            "[dshow @ 000001c2]   Alternative name \"@device_pnp_\\\\?\\usb#vid_046d&pid_082d\"\n" +
            "[dshow @ 000001c2] \"Some Capture Card\" (none)\n" +
            "[dshow @ 000001c2]   Alternative name \"@device_pnp_card\"\n" +
            "[dshow @ 000001c2] \"Microphone (HD Pro Webcam C920)\" (audio)\n" +
            "[dshow @ 000001c2]   Alternative name \"@device_cm_{33D9A762}\\wave_{1234}\"\n" +
            "[dshow @ 000001c2] Could not enumerate audio only devices (or none found).\n"

    @Test
    fun theListingGivesEachDeviceItsAlternativeNameAndKind() {
        val sources = parseDshowListing(log)

        assertEquals(
            listOf(
                Triple("@device_sw_{860BB310-5D01-11D0}\\{5C2CD55C-92AD-4999}", "Unity Video Capture", true),
                Triple("@device_pnp_\\\\?\\usb#vid_046d&pid_082d", "HD Pro Webcam C920", true),
                Triple("@device_pnp_card", "Some Capture Card", true),
                Triple("@device_cm_{33D9A762}\\wave_{1234}", "Microphone (HD Pro Webcam C920)", false),
            ),
            sources.map { Triple(it.name, it.description, it.isVideo) },
        )
    }

    @Test
    fun aListingWithNoDeviceIsEmpty() {
        assertEquals(
            emptyList(),
            parseDshowListing("[dshow @ 01] Could not enumerate video devices (or none found).\n"),
        )
    }

    @Test
    fun aDeviceLineWithoutItsAlternativeNameIsLeftOut() {
        assertEquals(emptyList(), parseDshowListing("[dshow @ 01] \"Half\" (video)\n[dshow @ 01] unrelated\n"))
    }
}
