@file:OptIn(InternalCameraKApi::class)

package com.kashif.cameraK.compose

import androidx.lifecycle.ViewModel
import com.kashif.cameraK.state.CameraConfiguration
import com.kashif.cameraK.state.CameraKPlugin
import com.kashif.cameraK.state.CameraKStateHolder
import com.kashif.cameraK.utils.InternalCameraKApi
import kotlinx.coroutines.Job

/**
 * Builds a holder for a configuration and the plugins present at creation, starting once the holder
 * it replaces has closed ([startAfter]).
 */
internal fun interface HolderFactory {
    fun create(config: CameraConfiguration, plugins: List<CameraKPlugin>, startAfter: Job?): CameraKStateHolder
}

/**
 * Keeps `rememberCameraKStateHolder`'s holder across an Activity recreation (design §1.5): at most
 * one holder, for one call site. A new configuration closes the old holder and builds a new one,
 * whose engine starts after the old engine has closed; [onCleared] closes the holder. Called from
 * the main thread only, by composition and its effects.
 */
internal class CameraKHolderViewModel : ViewModel() {
    private var holder: CameraKStateHolder? = null
    private var config: CameraConfiguration? = null

    // The plugins last applied to the holder, by instance: a recreated composition has new ones.
    private var applied: List<CameraKPlugin> = emptyList()

    /** The holder for [config]: the one kept, or a new one, built with [plugins] and [seed]'s config. */
    fun holderFor(
        config: CameraConfiguration,
        plugins: List<CameraKPlugin>,
        seed: (CameraConfiguration) -> CameraConfiguration,
        factory: HolderFactory,
    ): CameraKStateHolder {
        val kept = holder
        if (kept != null && this.config == config) return kept
        val startAfter = kept?.closeCompletion
        kept?.close()
        // Only a holder built in a fresh ViewModel (first use, or a new process) takes the saved choices.
        return factory.create(if (kept == null) seed(config) else config, plugins, startAfter).also {
            holder = it
            this.config = config
            applied = plugins
        }
    }

    /**
     * Detaches the plugins no longer in [plugins] and attaches the new ones, measured by instance
     * against the list last applied, detaching first, so a recreated composition's new instances
     * replace the old ones instead of running beside them.
     */
    fun applyPlugins(plugins: List<CameraKPlugin>) {
        val current = holder ?: return
        applied = current.replacePlugins(applied, plugins)
    }

    override fun onCleared() {
        holder?.close()
        holder = null
    }
}

/** Detaches what [old] has and [new] lacks, then attaches what [new] adds, by instance; returns [new]. */
internal fun CameraKStateHolder.replacePlugins(
    old: List<CameraKPlugin>,
    new: List<CameraKPlugin>,
): List<CameraKPlugin> {
    old.filter { o -> new.none { it === o } }.forEach(::detachPlugin)
    new.filter { n -> old.none { it === n } }.forEach(::attachPlugin)
    return new
}
