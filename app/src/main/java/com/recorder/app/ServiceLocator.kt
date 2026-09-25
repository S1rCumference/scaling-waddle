package com.recorder.app

import android.content.Context
import com.recorder.core.llm.local.LocalModelProvider
import com.recorder.core.storage.RecorderDatabase
import com.recorder.core.storage.RecorderSettings

/**
 * Hand-rolled composition root. Four objects, no scopes, nothing a DI framework would help
 * with — and three fewer than in 2.4, now that there is no key store, no connector registry
 * and no provider factory to choose between backends that no longer exist.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val database: RecorderDatabase by lazy { RecorderDatabase.get(appContext) }
    val settings: RecorderSettings by lazy { RecorderSettings(appContext) }

    /** The one model, for the one thing it does. */
    val correctionProvider: LocalModelProvider by lazy { LocalModelProvider(appContext) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
