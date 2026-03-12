package de.adesso.agenticaifunctioncalling.data.dispatcher

import android.content.Context
import android.content.Intent
import de.adesso.agenticaifunctioncalling.model.FunctionCall
import de.adesso.agenticaifunctioncalling.model.FunctionResult

/**
 * Executes actions described by a [FunctionCall].
 *
 * Navigation calls are intercepted upstream in [AgentViewModel] before ever
 * reaching this dispatcher.
 *
 * The only remaining system action is openApp. dispatch() uses keyword-matching
 * on the function name so variants the LLM might emit (open_app, launchApp,
 * openApplication, startApp, …) all resolve correctly — mirroring the same
 * format-agnostic approach used for navigation in AgentViewModel.
 */
class FunctionDispatcher(private val context: Context) {

    fun dispatch(call: FunctionCall): FunctionResult {
        val name = call.name.lowercase()
        return when {
            name.containsAny("openapp", "open_app", "launch", "startapp", "openapplication") ->
                openApp(call.args)

            else ->
                FunctionResult.Failure("Unbekannte Funktion: ${call.name}")
        }
    }


    private fun openApp(args: Map<String, String>): FunctionResult {
        return runCatching {
            val pkg = args.firstValue("packageName", "package", "pkg", "app")
                ?: return FunctionResult.Failure("Argument 'packageName' fehlt.")
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
                ?: return FunctionResult.Failure("App nicht installiert: $pkg")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            FunctionResult.Success("App $pkg geöffnet.")
        }.getOrElse { FunctionResult.Failure("App-Fehler: ${it.message}") }
    }

    /** True if this string contains any of the given [keywords] (case-insensitive). */
    private fun String.containsAny(vararg keywords: String) =
        keywords.any { this.contains(it, ignoreCase = true) }

    /** Returns the first non-null value found under any of the given [keys]. */
    private fun Map<String, String>.firstValue(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { this[it] }
}
