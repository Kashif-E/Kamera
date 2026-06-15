package org.company.app

import kotlinx.coroutines.CoroutineScope

actual fun getTFliteRunner(): (ByteArray.(CoroutineScope) -> Unit)? = null
