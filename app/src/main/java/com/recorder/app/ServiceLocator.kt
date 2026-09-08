package com.recorder.app

import android.content.Context
import com.recorder.core.connectors.ConnectorRegistry
import com.recorder.core.connectors.ConnectorToolGateway
import com.recorder.core.connectors.GoogleConnectors
import com.recorder.core.connectors.RefreshTokenGoogleAuth
import com.recorder.core.llm.ApiKeyStore
import com.recorder.core.llm.LlmProviderFactory
import com.recorder.core.llm.TranscriptRag
import com.recorder.core.storage.RecorderDatabase
import com.recorder.core.storage.RecorderSettings

/**
 * Hand-rolled composition root. A DI framework would earn its keep in a larger app; here
 * it would mostly add build time to something that is six objects and no scopes.
 */
object ServiceLocator {

    private lateinit var appContext: Context

    val database: RecorderDatabase by lazy { RecorderDatabase.get(appContext) }
    val settings: RecorderSettings by lazy { RecorderSettings(appContext) }
    val apiKeys: ApiKeyStore by lazy { ApiKeyStore(appContext) }
    val providers: LlmProviderFactory by lazy { LlmProviderFactory(appContext, settings, apiKeys) }
    val rag: TranscriptRag by lazy { TranscriptRag(database.transcripts()) }

    val connectors: ConnectorRegistry by lazy {
        ConnectorRegistry().also { registry ->
            GoogleConnectors.registerAll(registry, RefreshTokenGoogleAuth(apiKeys))
        }
    }

    val connectorGateway: ConnectorToolGateway by lazy {
        ConnectorToolGateway(connectors, database.pendingActions())
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}
