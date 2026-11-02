package com.kashif.cameraK.result

import com.kashif.cameraK.error.CameraKError
import dev.drewhamilton.poko.Poko

/**
 * Closed hierarchy: the outcome of a CameraK command, a value or a typed [CameraKError].
 *
 * CameraK's own type because `kotlin.Result` cannot carry a typed error. Success and Failure compare
 * by value.
 */
public sealed interface CameraKResult<out T> {
    @Poko
    public class Success<out T>(public val value: T) : CameraKResult<T>

    @Poko
    public class Failure(public val error: CameraKError) : CameraKResult<Nothing>
}

/** The value, or null on failure. */
public fun <T> CameraKResult<T>.getOrNull(): T? = (this as? CameraKResult.Success)?.value

/** The value, or what [onFailure] returns for the error. */
public inline fun <T> CameraKResult<T>.getOrElse(onFailure: (CameraKError) -> T): T = when (this) {
    is CameraKResult.Success -> value
    is CameraKResult.Failure -> onFailure(error)
}

/** The error, or null on success. */
public val CameraKResult<*>.errorOrNull: CameraKError? get() = (this as? CameraKResult.Failure)?.error

/** A success with [transform] applied to its value; a failure unchanged. */
public inline fun <T, R> CameraKResult<T>.map(transform: (T) -> R): CameraKResult<R> = when (this) {
    is CameraKResult.Success -> CameraKResult.Success(transform(value))
    is CameraKResult.Failure -> this
}

/** [onSuccess] of the value or [onFailure] of the error. */
public inline fun <T, R> CameraKResult<T>.fold(onSuccess: (T) -> R, onFailure: (CameraKError) -> R): R = when (this) {
    is CameraKResult.Success -> onSuccess(value)
    is CameraKResult.Failure -> onFailure(error)
}

/** Runs [action] on the value of a success; returns this result. */
public inline fun <T> CameraKResult<T>.onSuccess(action: (T) -> Unit): CameraKResult<T> {
    if (this is CameraKResult.Success) action(value)
    return this
}

/** Runs [action] on the error of a failure; returns this result. */
public inline fun <T> CameraKResult<T>.onFailure(action: (CameraKError) -> Unit): CameraKResult<T> {
    if (this is CameraKResult.Failure) action(error)
    return this
}
