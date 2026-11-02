package com.kashif.cameraK.result

import com.kashif.cameraK.error.CameraKError

/**
 * Closed hierarchy: the outcome of a CameraK command, a value or a typed [CameraKError].
 *
 * CameraK's own type because `kotlin.Result` cannot carry a typed error. Success and Failure compare
 * by value.
 */
public sealed interface CameraKResult<out T> {
    public class Success<out T>(public val value: T) : CameraKResult<T>

    public class Failure(public val error: CameraKError) : CameraKResult<Nothing>
}

/** The value, or null on failure. */
public fun <T> CameraKResult<T>.getOrNull(): T? = TODO("S1' stub")

/** The value, or what [onFailure] returns for the error. */
public inline fun <T> CameraKResult<T>.getOrElse(onFailure: (CameraKError) -> T): T = TODO("S1' stub")

/** The error, or null on success. */
public val CameraKResult<*>.errorOrNull: CameraKError? get() = TODO("S1' stub")

/** A success with [transform] applied to its value; a failure unchanged. */
public inline fun <T, R> CameraKResult<T>.map(transform: (T) -> R): CameraKResult<R> = TODO("S1' stub")

/** [onSuccess] of the value or [onFailure] of the error. */
public inline fun <T, R> CameraKResult<T>.fold(onSuccess: (T) -> R, onFailure: (CameraKError) -> R): R =
    TODO("S1' stub")

/** Runs [action] on the value of a success; returns this result. */
public inline fun <T> CameraKResult<T>.onSuccess(action: (T) -> Unit): CameraKResult<T> = TODO("S1' stub")

/** Runs [action] on the error of a failure; returns this result. */
public inline fun <T> CameraKResult<T>.onFailure(action: (CameraKError) -> Unit): CameraKResult<T> = TODO("S1' stub")
