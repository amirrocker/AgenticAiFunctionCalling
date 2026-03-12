package de.adesso.agenticaifunctioncalling.di

import de.adesso.agenticaifunctioncalling.data.dispatcher.FunctionDispatcher
import de.adesso.agenticaifunctioncalling.data.engine.LiteRtEngine
import de.adesso.agenticaifunctioncalling.data.parser.FunctionCallParser
import de.adesso.agenticaifunctioncalling.data.repository.ModelRepository
import de.adesso.agenticaifunctioncalling.ui.agent.AgentViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.dsl.module

val appModule = module {

    single<HttpClient> {
        HttpClient(Android) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
            install(Logging) {
                level = LogLevel.HEADERS  // change to BODY for debugging
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 60_000
                socketTimeoutMillis = 60_000     // 0 = no timeout for large downloads
            }
        }
    }

    single {
        ModelRepository(
            androidContext(),
            get(),
            hfApiKey = "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"
        )
    }

    single { LiteRtEngine(get<ModelRepository>().modelPath) }

    single { FunctionDispatcher(androidContext()) }

    single { FunctionCallParser }

    viewModel {
        AgentViewModel(
            engine = get(),
            dispatcher = get(),
            repository = get(),
            context = androidContext(),
        )
    }
}