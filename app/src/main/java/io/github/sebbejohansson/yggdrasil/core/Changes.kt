package io.github.sebbejohansson.yggdrasil.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A counter a part bumps on every write, so screens know to reload. */
class Changes {
    private val version = MutableStateFlow(0L)
    val flow: StateFlow<Long> = version.asStateFlow()
    fun bump() = version.update { it + 1 }
}
