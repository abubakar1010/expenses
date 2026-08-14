package com.app.finance.di

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * The bridge between the hand-rolled [AppContainer] and `viewModel()`.
 *
 * With no DI framework there is no generated factory, and AndroidX's
 * `viewModelFactory { initializer { } }` builder needs a `KClass` per
 * initializer. This is the whole of the machinery Hilt would otherwise be
 * carried in for — six lines, no annotation processor, nothing constructed at
 * startup.
 */
inline fun <reified VM : ViewModel> viewModelFactory(
    crossinline create: () -> VM,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}
