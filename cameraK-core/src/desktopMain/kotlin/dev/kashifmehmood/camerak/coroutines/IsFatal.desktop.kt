package dev.kashifmehmood.camerak.coroutines

internal actual fun Throwable.isFatal(): Boolean = this is VirtualMachineError
