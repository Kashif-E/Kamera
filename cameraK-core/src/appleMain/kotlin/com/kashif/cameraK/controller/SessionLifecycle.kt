package com.kashif.cameraK.controller

import com.kashif.cameraK.controller.CustomCameraController.CameraException
import com.kashif.cameraK.enums.AspectRatio
import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import platform.AVFoundation.AVCaptureInput
import platform.AVFoundation.AVCaptureMovieFileOutput
import platform.AVFoundation.AVCaptureOutput
import kotlin.concurrent.Volatile

private const val TAG = "CameraK"

/**
 * A controller's AVCaptureSession: made ahead of setup or by it, configured in transactions on [queue],
 * started, stopped and torn down. It holds the configuration changes plugins queue, the still preset,
 * and the movie output's transactions a recording makes. [onStarted] runs on the queue each time the
 * session starts running.
 */
internal class SessionLifecycle(
    private val queue: SessionQueue,
    private val sessionFactory: () -> CaptureSessionOps,
    private val aspectRatio: AspectRatio,
    private val targetResolution: Pair<Int, Int>?,
    private val onStarted: () -> Unit,
    private val disableDeferredStart: (AVCaptureOutput) -> Unit,
    private val reportError: (CameraException) -> Unit,
) {
    /** Bumped by [invalidateSetups] so a setup requested before it never reports ready. */
    private val generation = atomic(0)

    /** The setup generation now; a setup that began in an older one does not report ready. */
    val setupGeneration: Int get() = generation.value

    /** Written only on [queue]; read elsewhere only to check presence. */
    @Volatile
    var session: CaptureSessionOps? = null
        private set

    /**
     * The session [prepareSession] made ahead of setup, so a preview layer can show it before the
     * camera starts; setup configures this one instead of asking [sessionFactory] for another.
     */
    @Volatile
    private var preparedSession: CaptureSessionOps? = null

    // Configuration changes for plugin outputs (Apple WWDC pattern). Touched only on the queue.
    private val pendingConfigurations = mutableListOf<() -> Unit>()

    /**
     * The preset stills run at: the one setup chose ([presetCandidates]), which a recording's preset
     * gives way to and returns to afterwards. Session queue only; null before setup.
     */
    var stillPreset: String? = null
        private set

    /** The session: configured, or prepared and waiting for its first configuration; null before either. */
    val sessionOps: CaptureSessionOps? get() = session ?: preparedSession

    /** Whether setup has configured the session (and it has not been torn down since). */
    val isConfigured: Boolean get() = session != null

    /** Replaces the session with [ops]; the deprecated captureSession setter's. */
    fun replaceSession(ops: CaptureSessionOps?) {
        session = ops
    }

    /** Makes a setup requested before now never report ready. */
    fun invalidateSetups() {
        generation.incrementAndGet()
    }

    /**
     * Makes the session now, on the calling thread, without configuring it: the engine calls it once
     * from its constructor, so a preview layer is bound to the session before the camera starts.
     */
    fun prepareSession(): CaptureSessionOps = sessionOps ?: sessionFactory().also { preparedSession = it }

    /**
     * The session setup configures, published before configuring so changes applied during setup
     * (safeAddOutput) reach it. Session queue only.
     */
    fun beginSetup(): CaptureSessionOps = (preparedSession ?: sessionFactory()).also { session = it }

    /** Forgets the session of a setup that failed. Session queue only. */
    fun discardSession() {
        session = null
    }

    /**
     * Tears the session down: stopped, then emptied of its inputs and outputs in one transaction, so it
     * holds no camera however long the session object itself lives on. Kotlin/Native releases the
     * AVCaptureSession only when its collector frees the engine, and every closed session still holding
     * a camera's input slowed the next start on that camera until starts timed out. Forgets the session
     * and its still preset. Session queue only.
     */
    fun tearDownOnQueue() {
        (session ?: preparedSession)?.let { ops ->
            ops.tearDown()
            ops.beginConfiguration()
            try {
                ops.inputs.forEach(ops::removeInput)
                ops.outputs.forEach(ops::removeOutput)
            } finally {
                ops.commitConfiguration()
            }
        }
        session = null
        preparedSession = null
        stillPreset = null
    }

    /**
     * Sets the best preset the session can take with its current input. Assigning one it cannot
     * take raises an NSException Kotlin cannot catch, so each is checked first. Runs on the session
     * queue inside the setup transaction.
     */
    fun applySessionPreset(ops: CaptureSessionOps) {
        val candidates = presetCandidates(aspectRatio, targetResolution)
        val preset = candidates.firstOrNull(ops::canSetSessionPreset)
        if (preset == null) {
            CameraKLogger.w(TAG, "the session can take none of the presets $candidates; keeping its default")
            return
        }
        if (preset != candidates.first()) {
            CameraKLogger.w(TAG, "the session cannot take preset ${candidates.first()}; using $preset")
        }
        ops.setSessionPreset(preset)
        stillPreset = preset
    }

    /**
     * Queues a configuration change to be applied atomically (Apple WWDC pattern) on the session
     * queue. Used by plugins to safely add outputs without crashing; callable from any thread.
     *
     * A change queued before the session is set up joins the setup transaction. Once the session
     * exists, the change is applied in its own transaction whether the session is running, stopped
     * or interrupted, so an output a plugin removes never waits for a start that may not come.
     *
     * @param change Lambda to execute within beginConfiguration/commitConfiguration block
     */
    fun queueConfigurationChange(change: () -> Unit) {
        queue.async {
            pendingConfigurations.add(change)
            val ops = session ?: return@async
            processPendingConfigurations(ops)
        }
    }

    /**
     * Processes all queued configuration changes in a single transaction. Runs on the session queue,
     * which keeps startRunning out of any open configuration.
     */
    private fun processPendingConfigurations(ops: CaptureSessionOps) {
        if (pendingConfigurations.isEmpty()) return
        ops.beginConfiguration()
        try {
            runPendingConfigurations()
        } finally {
            ops.commitConfiguration()
        }
    }

    /** Runs the queued changes inside the caller's open configuration transaction. */
    fun runPendingConfigurations() {
        val changesToApply = pendingConfigurations.toList()
        pendingConfigurations.clear()

        for (change in changesToApply) {
            try {
                change()
            } catch (e: Exception) {
                CameraKLogger.e(TAG, "Error processing configuration change", e)
            }
        }
    }

    /**
     * Safely adds an output to the capture session.
     * Inside a queueConfigurationChange block it adds the output at once; from any other thread it
     * queues the addition as a configuration change.
     */
    fun safeAddOutput(output: AVCaptureOutput) {
        if (!queue.isCurrent) {
            queueConfigurationChange { safeAddOutput(output) }
            return
        }
        val session = session ?: return
        if (session.canAddOutput(output)) {
            session.addOutput(output)
        } else {
            CameraKLogger.w(TAG, "the capture session refused an output; it was not added")
        }
    }

    /** Whether [output] is attached to the session. Call it on the session queue (inside a change). */
    fun containsOutput(output: AVCaptureOutput): Boolean = session?.outputs?.contains(output) == true

    /**
     * Safely removes an output previously added via [safeAddOutput], in the same batching as
     * [queueConfigurationChange].
     */
    fun safeRemoveOutput(output: AVCaptureOutput) {
        queueConfigurationChange {
            val session = session ?: return@queueConfigurationChange
            if (session.outputs.contains(output)) {
                session.removeOutput(output)
            }
        }
    }

    /**
     * Adds the input [create] makes unless an attached input already satisfies [isPresent], as one
     * queued configuration change. An input [create] cannot make, or one the session refuses, is logged
     * and reported as a ConfigurationError naming [what], with the platform's error as its cause.
     */
    fun addInputUnlessPresent(
        what: String,
        isPresent: (AVCaptureInput) -> Boolean,
        create: () -> Result<AVCaptureInput>,
    ) {
        queueConfigurationChange {
            val ops = session ?: return@queueConfigurationChange
            if (ops.inputs.any(isPresent)) return@queueConfigurationChange
            val failure = create().fold(
                onSuccess = { input ->
                    if (ops.canAddInput(input)) {
                        ops.addInput(input)
                        null
                    } else {
                        CameraException.ConfigurationError("The capture session refused $what")
                    }
                },
                onFailure = { cause ->
                    val reported = (cause as? NSErrorException)?.error?.let { ": ${it.summary}" }.orEmpty()
                    CameraException.ConfigurationError("Could not add $what$reported", cause)
                },
            ) ?: return@queueConfigurationChange
            CameraKLogger.e(TAG, "adding $what failed", failure)
            reportError(failure)
        }
    }

    /**
     * Session queue, before the session is configured: [change] runs inside the setup transaction, as
     * a change queued before setup does.
     */
    fun joinSetupOnQueue(change: () -> Unit) {
        pendingConfigurations.add(change)
    }

    /**
     * Adds a recording's movie [output], and the microphone [microphone] makes when it is given, and
     * sets [preset] in one configuration transaction (design §5). Session queue only; returns the
     * microphone input added (null without one), or why the output could not be added.
     */
    fun configureMovieOnQueue(
        output: AVCaptureMovieFileOutput,
        microphone: (() -> Result<AVCaptureInput>)?,
        preset: String?,
    ): Result<AVCaptureInput?> {
        val ops = session ?: return Result.failure(CameraException.DeviceNotAvailable("The session is not set up"))
        ops.beginConfiguration()
        try {
            ops.setAutomaticallyConfiguresApplicationAudioSession(microphone != null)
            if (!ops.canAddOutput(output)) {
                return Result.failure(
                    CameraException.ConfigurationError("The capture session refused the movie output"),
                )
            }
            ops.addOutput(output)
            disableDeferredStart(output)
            val audio = microphone?.let { addMicrophone(ops, it) }
            when {
                preset == null -> Unit
                ops.canSetSessionPreset(preset) -> ops.setSessionPreset(preset)
                else -> CameraKLogger.w(TAG, "the session cannot take the recording's preset $preset")
            }
            return Result.success(audio)
        } finally {
            ops.commitConfiguration()
        }
    }

    private fun addMicrophone(ops: CaptureSessionOps, microphone: () -> Result<AVCaptureInput>): AVCaptureInput? {
        val failure = microphone().fold(
            onSuccess = { input ->
                if (ops.canAddInput(input)) {
                    ops.addInput(input)
                    return input
                }
                CameraException.ConfigurationError("The capture session refused the microphone")
            },
            onFailure = { cause ->
                val reported = (cause as? NSErrorException)?.error?.let { ": ${it.summary}" }.orEmpty()
                CameraException.ConfigurationError("Could not add the microphone$reported", cause)
            },
        )
        CameraKLogger.e(TAG, "adding the microphone failed", failure)
        reportError(failure)
        return null
    }

    /**
     * Sets [preset], a recording quality's, or the still preset when it is null, in one configuration
     * transaction: a prepared movie output's recordings (w6 decision G2). Session queue only.
     */
    fun setSessionPresetOnQueue(preset: String?) {
        val ops = session ?: return
        val wanted = preset ?: stillPreset ?: return
        ops.beginConfiguration()
        try {
            if (ops.canSetSessionPreset(wanted)) {
                ops.setSessionPreset(wanted)
            } else {
                CameraKLogger.w(TAG, "the session cannot take preset $wanted")
            }
        } finally {
            ops.commitConfiguration()
        }
    }

    /**
     * Removes a finished recording's [output] and [microphone] and returns the session to the still
     * preset, in one transaction, so the session and the microphone indicator are photo-only again.
     * Session queue only.
     */
    fun releaseMovieOnQueue(output: AVCaptureMovieFileOutput, microphone: AVCaptureInput?) {
        val ops = session ?: return
        ops.beginConfiguration()
        try {
            if (output in ops.outputs) ops.removeOutput(output)
            if (microphone != null && microphone in ops.inputs) ops.removeInput(microphone)
            stillPreset?.let { if (ops.canSetSessionPreset(it)) ops.setSessionPreset(it) }
            ops.setAutomaticallyConfiguresApplicationAudioSession(false)
        } finally {
            ops.commitConfiguration()
        }
    }

    /** Starts the configured session unless it runs, then runs [onStarted]. Session queue only. */
    fun startRunningOnQueue() {
        val ops = session ?: return
        if (!ops.isRunning) {
            ops.startRunning()
            onStarted()
        }
    }

    /** Stops the session if it runs or the system holds it interrupted. Session queue only. */
    fun stopRunningOnQueue() {
        val ops = sessionOps ?: return
        if (ops.isRunning || ops.isInterrupted) ops.stopRunning()
    }
}
