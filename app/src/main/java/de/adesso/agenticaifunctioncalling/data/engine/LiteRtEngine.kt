package de.adesso.agenticaifunctioncalling.data.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File

private const val TAG = "LiteRtEngine"
const val DEFAULT_MAX_TOKEN = 1024

/**
 * Wraps the LiteRT-LM [Engine] and [Conversation] APIs.
 *
 * LiteRT-LM reference: https://github.com/google-ai-edge/LiteRT-LM
 * Model format        : .litertlm  (Gemma 3 1B-IT recommended)
 * GPU backend         : requires libOpenCL.so declared in AndroidManifest
 *
 * Call [initialize] once (on a background dispatcher – it can take ~10 s).
 * Call [chat] to stream tokens as a Flow<String>.
 * Call [close] in onCleared / onDestroy.
 */

class LiteRtEngine(
    private val modelPath: String,

) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    fun initialize(context: Context) {
        Log.d(TAG, "Initializing LiteRT-LM engine from $modelPath")

        // Ensure model file exists and is readable
        require(File(modelPath).exists() && File(modelPath).canRead()) {
            "Model file does not exist or is not readable: $modelPath"
        }

        // init engine
        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            maxNumTokens = DEFAULT_MAX_TOKEN,
            cacheDir = if(modelPath.startsWith("/data/local/tmp"))
                context.getExternalFilesDir(null)?.absolutePath
            else null,
        )
        engine = Engine(config).apply { initialize() }

        // setup conversation
        val configWithTool = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
//            tools = listOf(tool(toolset))
        )

        // create single session (only one supported at a time)
        conversation = engine?.createConversation(configWithTool)
    }

    /**
     * Sends [userMessage] and returns a cold [Flow] that emits partial
     * text tokens as the model streams them.
     *
     * The raw output may contain <function_call> tags; callers should pass it
     * through [FunctionCallParser] after collecting the full response.
     */
    fun chat(userMessage: String): Flow<String> {
        val conv = requireNotNull(conversation) {
            "Engine not initialized – call initialize() first."
        }
        return conv.sendMessageAsync(userMessage).map { message: Message ->
            println("Received message with ${message.contents.contents.size} content parts.")
            message.contents.contents.joinToString("")
        }
    }

    fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
        Log.d(TAG, "Engine closed.")
    }

    companion object {
        private val SYSTEM_PROMPT = """
            Du bist ein hilfreicher Android-Assistent. Antworte auf Deutsch.

            Wenn der Nutzer eine konkrete Aktion verlangt, schreibe zuerst
            eine kurze Antwort, dann füge GENAU EINEN dieser Tags an:

            Kalender:
            <function_call>{"name":"addCalendarEntry","args":{"title":"...","date":"YYYY-MM-DD","time":"HH:MM","durationMinutes":"60"}}</function_call>

            SMS:
            <function_call>{"name":"sendSMS","args":{"number":"+49...","message":"..."}}</function_call>

            Alarm:
            <function_call>{"name":"setAlarm","args":{"time":"HH:MM","label":"..."}}</function_call>

            App öffnen:
            <function_call>{"name":"openApp","args":{"packageName":"com.example.app"}}</function_call>

            Regeln:
            - Tags NUR wenn eine Aktion wirklich nötig ist.
            - Kein Tag = reiner Gesprächstext.
            - Immer zuerst Text, dann Tag – nie nur Tag ohne Erklärung.
            - Alle Argumente als Strings (kein Zahl-Typ, keine Null-Werte).
        """.trimIndent()
    }
}