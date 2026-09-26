package io.github.mangi.eta.agent.model

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 10_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    const val MODEL_READ_TIMEOUT_MS = 300_000L

    val modelClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(MODEL_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .dispatcher(Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 16
            })
            .build()
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .build()
    }
}
