package com.bonevane.bridge

import android.content.Context

/** The application context, for singletons that need Prefs but have no Context. */
object AppContext {
    @Volatile lateinit var value: Context
}
