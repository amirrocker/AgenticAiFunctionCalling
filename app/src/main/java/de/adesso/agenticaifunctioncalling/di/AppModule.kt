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

/**
 * Single Koin module for the whole app.
 * Scoped as singletons where lifecycle matters (Engine, ViewModel).
 */
val appModule = module {

    // ── Ktor HttpClient ───────────────────────────────────────────────────────
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

    // ── Repository ────────────────────────────────────────────────────────────
//    single { ModelRepository(androidContext(), get()) }
    single { ModelRepository(androidContext(), get(), hfApiKey = "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") }

    // ── LiteRT-LM Engine ──────────────────────────────────────────────────────
    // Created lazily; path resolved from ModelRepository at runtime
    single { LiteRtEngine(get<ModelRepository>().modelPath) }

    // ── Dispatcher ────────────────────────────────────────────────────────────
    single { FunctionDispatcher(androidContext()) }

    // ── Parser (stateless object – still registered for testability) ──────────
    single { FunctionCallParser }

    // ── ViewModel ─────────────────────────────────────────────────────────────
    viewModel {
        AgentViewModel(
            context = androidContext(),
            engine = get(),
            parser = get(),
            dispatcher = get(),
            repository = get(),
        )
    }
}