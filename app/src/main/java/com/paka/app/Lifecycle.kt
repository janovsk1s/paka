package com.paka.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Tracks whether the hosting activity is at least STARTED (visible foreground).
 *
 * Compose keeps `LaunchedEffect` coroutines alive while the activity is stopped,
 * so timer loops must gate on this to avoid waking the CPU in the background.
 */
@Composable
internal fun rememberIsForeground(): State<Boolean> {
    val owner = LocalContext.current as? LifecycleOwner
    val foreground = remember(owner) {
        mutableStateOf(owner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) ?: true)
    }
    DisposableEffect(owner) {
        val lifecycle = owner?.lifecycle
        if (lifecycle == null) {
            onDispose { }
        } else {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> foreground.value = true
                    Lifecycle.Event.ON_STOP -> foreground.value = false
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
    }
    return foreground
}
