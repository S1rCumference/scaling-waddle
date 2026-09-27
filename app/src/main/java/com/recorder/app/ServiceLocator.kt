package com.recorder.app

import android.content.Context
import com.recorder.core.storage.RecorderDatabase
import com.recorder.core.storage.RecorderSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled composition root: a database, the settings, and one scope for work that outlives a
 * screen. There is no model provider here any more — summarising talks to a hosted endpoint whose
 * address and key are settings, so there is nothing to construct and hold.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val database: RecorderDatabase by lazy { RecorderDatabase.get(appContext) }
    val settings: RecorderSettings by lazy { RecorderSettings(appContext) }

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
