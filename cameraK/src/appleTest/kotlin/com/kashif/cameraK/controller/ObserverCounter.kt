@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.kashif.cameraK.controller

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.interpretObjCPointerOrNull
import kotlinx.cinterop.invoke
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toLong
import platform.Foundation.NSLock
import platform.Foundation.NSString
import platform.objc.class_getInstanceMethod
import platform.objc.method_getImplementation
import platform.objc.method_setImplementation
import platform.objc.objc_getClass
import platform.objc.sel_registerName

private typealias AddObserver = CFunction<
    (
        COpaquePointer?,
        COpaquePointer?,
        COpaquePointer?,
        COpaquePointer?,
        COpaquePointer?,
        COpaquePointer?,
    ) -> COpaquePointer?,
    >
private typealias RemoveObserver = CFunction<(COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Unit>

private val counterLock = NSLock()
private var watchedName: String? = null
private val liveTokens = mutableSetOf<Long>()

private var originalAdd: CPointer<AddObserver>? = null

private var originalRemove: CPointer<RemoveObserver>? = null

private fun <T> counting(block: () -> T): T {
    counterLock.lock()
    try {
        return block()
    } finally {
        counterLock.unlock()
    }
}

private val addHook = staticCFunction {
        center: COpaquePointer?,
        sel: COpaquePointer?,
        name: COpaquePointer?,
        obj: COpaquePointer?,
        queue: COpaquePointer?,
        block: COpaquePointer?,
    ->
    val token = originalAdd!!(center, sel, name, obj, queue, block)
    val observedName = name?.let { interpretObjCPointerOrNull<NSString>(it.rawValue)?.toString() }
    if (token != null && observedName != null) {
        counting { if (observedName == watchedName) liveTokens += token.toLong() }
    }
    token
}

private val removeHook = staticCFunction { center: COpaquePointer?, sel: COpaquePointer?, observer: COpaquePointer? ->
    observer?.let { counting { liveTokens -= it.toLong() } }
    originalRemove!!(center, sel, observer)
}

/**
 * Counts the block-based NotificationCenter observers registered for [name] while [block] runs and
 * not removed with removeObserver: by the time it returns. NotificationCenter has no API that lists
 * observers, so for the duration of [block] this swaps the implementations of
 * addObserverForName:object:queue:usingBlock: and removeObserver: for ones that record the tokens
 * and call through to the originals.
 */
internal fun blockObserversLeftBehind(name: String, block: () -> Unit): Int {
    val cls = objc_getClass("NSNotificationCenter") as ObjCClass?
    val add = class_getInstanceMethod(cls, sel_registerName("addObserverForName:object:queue:usingBlock:"))
    val remove = class_getInstanceMethod(cls, sel_registerName("removeObserver:"))
    check(add != null && remove != null) { "NSNotificationCenter lacks the methods the counter swaps" }
    counting {
        watchedName = name
        liveTokens.clear()
    }
    // The originals are known before the hooks can run.
    originalAdd = method_getImplementation(add)?.reinterpret()
    originalRemove = method_getImplementation(remove)?.reinterpret()
    method_setImplementation(add, addHook.reinterpret())
    method_setImplementation(remove, removeHook.reinterpret())
    try {
        block()
    } finally {
        method_setImplementation(add, originalAdd?.reinterpret())
        method_setImplementation(remove, originalRemove?.reinterpret())
    }
    return counting {
        watchedName = null
        liveTokens.size.also { liveTokens.clear() }
    }
}
