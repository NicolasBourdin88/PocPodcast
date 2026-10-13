package fr.podcast.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout

/** The HTTP client every app uses; the engine comes from the platform (OkHttp, Darwin, CIO). */
fun podcastHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        connectTimeoutMillis = 5_000
        requestTimeoutMillis = 15_000
    }
}
