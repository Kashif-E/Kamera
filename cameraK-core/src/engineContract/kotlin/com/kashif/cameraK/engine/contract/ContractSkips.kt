package com.kashif.cameraK.engine.contract

/**
 * The steps the kit could not run on one engine, and why. A skipped step is reported, never passed
 * silently: every driver test prints the list, and a case whose every step was skipped fails.
 */
internal class ContractSkips {
    private val entries = mutableListOf<String>()

    val skipped: List<String> get() = entries.toList()

    fun record(case: String, step: String, reason: String) {
        entries += "$case: $step skipped ($reason)"
    }

    fun report(): String = entries.joinToString(separator = "\n", prefix = "Contract kit, skipped steps:\n")
}
