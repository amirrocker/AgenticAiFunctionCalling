package de.adesso.agenticaifunctioncalling.data.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
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

    // ── Lifecycle ─────────────────────────────────────────────────────────────


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

        // setup tool
//        val toolset = GuardrailToolSet(blackboardDao)
        val configWithTool = ConversationConfig(
            systemInstruction = Contents.of(SYSTEM_PROMPT),
//            systemInstruction = Contents.of("Act as an Agent with multiple roles: researcher, validator, and writer."),
//            tools = listOf(tool(toolset))
        )

        // create single session (only one supported at a time)
        conversation = engine?.createConversation(configWithTool)


//        // Load native dependencies in order (some depend on others)
//        val nativeLibraryOrder = listOf(
//            // Try standard NDK C++ library first
//            "c++_shared",
//            // Then LiteRT-LM library variations
//            "litertlm",
//            "litertlm_android",
//            "genai_litertlm"
//        )
//
//        val loadedLibraries = mutableListOf<String>()
//
//        for (libName in nativeLibraryOrder) {
//            try {
//                System.loadLibrary(libName)
//                loadedLibraries.add(libName)
//                Log.d(TAG, "Native library '$libName' loaded successfully")
//            } catch (e: UnsatisfiedLinkError) {
//                Log.w(TAG, "Could not load native library '$libName': ${e.message}")
//                // Continue trying other libraries
//            }
//        }
//
//        if (loadedLibraries.isEmpty()) {
//            Log.e(TAG, "Failed to load any native LiteRT-LM libraries")
//            throw RuntimeException(
//                "Could not load LiteRT-LM native libraries. " +
//                "Tried: $nativeLibraryOrder. " +
//                "Ensure: 1) LiteRT-LM AAR is in dependencies, " +
//                "2) Native .so files are packaged for arm64-v8a, " +
//                "3) Your device supports arm64-v8a architecture."
//            )
//        }
//
//        Log.d(TAG, "Successfully loaded native libraries: $loadedLibraries")
//
//        val engineConfig = EngineConfig(
//            modelPath = modelPath,
//            backend   = Backend.CPU()        // falls back to CPU automatically
//        )
//
//        Engine.setNativeMinLogSeverity(LogSeverity.ERROR) // silence noisy log for the TUI.
//
//        Engine(engineConfig).use { engine ->
//            engine.initialize()
//
//            val convConfig = ConversationConfig(
//                systemInstruction = Contents.of(SYSTEM_PROMPT),
//                samplerConfig = SamplerConfig(
//                    temperature = 0.1,     // low → reliable tag formatting
//                    topK        = 40,
//                    topP        = 0.95
//                )
//            )
//            conversation = engine.createConversation(convConfig)
//            Log.d(TAG, "Conversation ready.")
//        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

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
        // sendMessageAsync returns Flow<Message>; map to the text delta
        return conv.sendMessageAsync(userMessage).map { message: Message ->
//            message.content.parts.joinToString("") { it.text }
            println("Received message with ${message.contents.contents.size} content parts.")
            message.contents.contents.joinToString("")
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun close() {
        conversation?.close()
        engine?.close()
        conversation = null
        engine = null
        Log.d(TAG, "Engine closed.")
    }

    // ── System prompt ─────────────────────────────────────────────────────────

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