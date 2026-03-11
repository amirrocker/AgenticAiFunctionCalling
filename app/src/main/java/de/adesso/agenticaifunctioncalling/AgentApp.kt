package de.adesso.agenticaifunctioncalling

import android.app.Application
import de.adesso.agenticaifunctioncalling.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class AgentApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger(Level.DEBUG)
            androidContext(this@AgentApp)
            modules(appModule)
        }
    }
}