@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.kashif.cameraK.controller

import com.kashif.cameraK.utils.CameraKLogger
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import platform.Foundation.NSKeyValueObservingOptionNew
import platform.Foundation.addObserver
import platform.Foundation.removeObserver
import platform.darwin.NSObject
import platform.objc.class_addMethod
import platform.objc.object_getClass
import platform.objc.sel_registerName

/**
 * Calls [onChange], on whatever thread reports it, each time one of [keyPaths] of [subject] changes,
 * by key-value observing. Closing the result stops the observation; a report already on its way may
 * still arrive, but it calls nothing.
 *
 * Kotlin cannot override observeValueForKeyPath:ofObject:change:context:, which Foundation declares
 * in a category, so the method is added to the observer's Objective-C class at run time, and each
 * observation's callback travels in the KVO context.
 */
internal fun observeKeyPaths(subject: NSObject, keyPaths: List<String>, onChange: () -> Unit): AutoCloseable {
    check(observerMethodAdded) { "CameraK could not give its key-value observer its callback" }
    val target = KeyValueTarget(onChange)
    // Never disposed: a report racing the removal may still read it, so the target outlives the
    // observation and only its callback is released.
    val context = StableRef.create(target).asCPointer()
    val observer = KeyValueObserver()
    keyPaths.forEach {
        subject.addObserver(observer, forKeyPath = it, options = NSKeyValueObservingOptionNew, context = context)
    }
    val removed = atomic(false)
    return AutoCloseable {
        if (removed.compareAndSet(expect = false, update = true)) {
            keyPaths.forEach { subject.removeObserver(observer, forKeyPath = it) }
            target.callback.value = null
        }
    }
}

private class KeyValueTarget(callback: () -> Unit) {
    val callback = atomic<(() -> Unit)?>(callback)
}

/** The observer KVO calls; its class gets observeValueForKeyPath:ofObject:change:context: at run time. */
private class KeyValueObserver : NSObject()

private val observeValue = staticCFunction {
        _: COpaquePointer?,
        _: COpaquePointer?,
        _: COpaquePointer?,
        _: COpaquePointer?,
        _: COpaquePointer?,
        context: COpaquePointer?,
    ->
    try {
        context?.asStableRef<KeyValueTarget>()?.get()?.callback?.value?.invoke()
    } catch (e: Exception) {
        CameraKLogger.e("CameraK", "CameraK: handling a key-value report failed", e)
    }
    Unit
}

private val observerMethodAdded: Boolean by lazy {
    class_addMethod(
        object_getClass(KeyValueObserver()),
        sel_registerName("observeValueForKeyPath:ofObject:change:context:"),
        observeValue.reinterpret(),
        "v@:@@@^v",
    )
}
