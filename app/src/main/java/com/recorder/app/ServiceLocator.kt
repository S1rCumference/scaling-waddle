package com.recorder.app

import android.content.Context
import com.recorder.core.llm.local.LocalModelProvider
import com.recorder.core.storage.RecorderDatabase
import com.recorder.core.storage.RecorderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    /**
     * For work that belongs to the app rather than to a screen — writing a setting the user just
     * asked for, when the thing that asked has no scope of its own. A ViewModel scope would be
     * wrong here: these writes must not be cancelled because the screen went away, which is the
     * class of bug that made Stop not stick.
     */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
