package de.adesso.agenticaifunctioncalling.data.parser

import android.util.Log
import de.adesso.agenticaifunctioncalling.model.FunctionCall
import kotlinx.serialization.json.Json

/**
 * Parses <function_call>{…}</function_call> tags from raw LLM output.
 *
 * The LLM is prompted to emit JSON in the form:
 *   {"name":"addCalendarEntry","args":{"title":"…","date":"…","time":"…"}}
 *
 * All arg values are strings so the schema stays simple.
 */
object FunctionCallParser {

    private const val TAG = "FunctionCallParser"

    private val TAG_REGEX = Regex(
        """<function_call>\s*(.*?)\s*</function_call>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Extracts a FunctionCall from raw LLM text, or null if none present. */
    fun parse(llmOutput: String): FunctionCall? {
        val match = TAG_REGEX.find(llmOutput) ?: return null
        val raw = match.groupValues[1].trim()
        return try {
            json.decodeFromString<FunctionCall>(raw)
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse function_call JSON: $raw", e)
            null
        }
    }

    /** Returns the text with all function_call tags removed – safe to display. */
    fun stripTags(llmOutput: String): String =
        llmOutput.replace(TAG_REGEX, "").trim()
}

/**
 * The system prompt defines the exact function-calling protocol for the LLM.
 *
 * Key design decisions:
 * - Instructions are written in German to match the app's locale.
 * - Temperature will be set to 0.1 in the engine so format compliance is high.
 * - Only three functions are declared; the LLM cannot invent others.
 * - The LLM must ALWAYS write prose first, tag second – never tag-only responses.
 *
 * Suggestion for production: externalise this to a remote config so you can
 * tune the prompt A/B without shipping an app update. Store it in Firebase
 * Remote Config and cache it in DataStore.
 */
val AGENT_SYSTEM_PROMPT = """
    Du bist ein Banking- und Relocation-Assistent. Antworte IMMER auf Deutsch.

    Wenn der Nutzer eine der folgenden Seiten sehen möchte, schreibe eine kurze
    Antwort UND füge danach zwingend den passenden Tag ein.

    Deposits/Einlagen:
    <function_call>{"name":"navigateTo","args":{"destination":"deposits"}}</function_call>

    Relocation/Umzug (city optional):
    <function_call>{"name":"navigateTo","args":{"destination":"relocation","city":"Berlin"}}</function_call>

    Contracts/Verträge:
    <function_call>{"name":"navigateTo","args":{"destination":"contracts"}}</function_call>

    Chat/Startseite:
    <function_call>{"name":"navigateTo","args":{"destination":"chat"}}</function_call>

    App starten:
    <function_call>{"name":"openApp","args":{"packageName":"com.example.app"}}</function_call>

    REGELN:
    1. Navigationswunsch erkannt? Tag MUSS eingefügt werden – ohne Ausnahme.
    2. Nur Konversation ohne Aktion? Keinen Tag einfügen.
    3. Immer erst Text, dann Tag. Nie nur Tag allein.
    4. Alle Argumente als Strings.
""".trimIndent()

//  val AGENT_SYSTEM_PROMPT = """
//    Du bist ein hilfreicher Unternehmensassistent. Antworte auf Deutsch.
//    
//    Du hast Zugriff auf drei Informationsbereiche. Wenn der Nutzer nach einem
//    dieser Bereiche fragt, MUSST du ZUERST eine kurze Antwort schreiben und
//    DANACH genau einen der folgenden Tags anhängen:
//    
//    Kaution / Deposit-Übersicht:
//    <function_call>{"name":"showDeposits","args":{}}</function_call>
//    
//    Umzugsstatus / Relocation:
//    <function_call>{"name":"showRelocation","args":{}}</function_call>
//    
//    Verträge / Contracts:
//    <function_call>{"name":"showContracts","args":{}}</function_call>
//    
//    Regeln:
//    - Verwende NIEMALS mehr als einen Tag pro Antwort.
//    - Wenn kein Bereich passt, antworte nur mit Text – kein Tag.
//    - Schreibe immer zuerst Text, dann ggf. den Tag.
//    - Erfinde keine anderen function_call-Namen.
//    
//    Beispiele:
//    Nutzer: "Zeig mir meine Kautionen" → Text + showDeposits-Tag
//    Nutzer: "Wie läuft mein Umzug?" → Text + showRelocation-Tag  
//    Nutzer: "Welche Verträge laufen bald aus?" → Text + showContracts-Tag
//    Nutzer: "Wie ist das Wetter?" → Nur Text, kein Tag
//""".trimIndent()