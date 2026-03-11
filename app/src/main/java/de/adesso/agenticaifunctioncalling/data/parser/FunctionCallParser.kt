package de.adesso.agenticaifunctioncalling.data.parser

import kotlinx.serialization.json.Json
import android.util.Log
import de.adesso.agenticaifunctioncalling.model.FunctionCall

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