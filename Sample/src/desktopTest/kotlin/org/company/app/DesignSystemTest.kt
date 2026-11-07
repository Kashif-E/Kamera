@file:OptIn(ExperimentalCoroutinesApi::class)

package org.company.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.company.app.designsystem.ModeSwitcher
import org.company.app.designsystem.OptionRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The atoms with logic: a setting's row marks its one choice, and the mode switcher reports a tap. */
class DesignSystemTest {
    private enum class Mode { Photo, Video, Qr, Text, Analyze }

    @Test
    fun anOptionRowMarksExactlyTheSelectedOption() = runTest {
        // Five options wrap into two rows; the marking must not depend on the row.
        val options = listOf("Wide", "Ultra wide", "Telephoto", "Macro", "Default")
        var selected by mutableStateOf("Telephoto")
        SampleScene(this) {
            OptionRow(
                label = "Camera type",
                options = options,
                selected = selected,
                onSelect = { selected = it },
                optionLabel = { it },
            )
        }.use { scene ->
            val marked = { options.associateWith { scene.node(it).selected() } }
            assertEquals(options.associateWith { it == "Telephoto" }, marked())

            scene.tap(scene.node("Macro"))

            assertEquals("Macro", selected)
            assertEquals(options.associateWith { it == "Macro" }, marked())
        }
    }

    @Test
    fun theModeSwitcherCallsBackWithTheTappedMode() = runTest {
        val picked = mutableListOf<Mode>()
        SampleScene(this) {
            ModeSwitcher(
                modes = Mode.entries,
                selected = Mode.Photo,
                onSelect = { picked += it },
                label = { it.name.uppercase() },
            )
        }.use { scene ->
            assertEquals(true, scene.node("PHOTO").selected())
            scene.tap(scene.node("VIDEO"))
            scene.tap(scene.node("ANALYZE"))
        }
        assertEquals(listOf(Mode.Video, Mode.Analyze), picked)
    }

    @Test
    fun aDisabledModeSwitcherTakesNoTap() = runTest {
        var picked: Mode? = null
        SampleScene(this) {
            ModeSwitcher(
                modes = Mode.entries,
                selected = Mode.Video,
                onSelect = { picked = it },
                label = { it.name.uppercase() },
                enabled = false,
            )
        }.use { scene -> scene.tap(scene.node("PHOTO")) }
        assertNull(picked, "a mode was picked while recording")
    }
}
