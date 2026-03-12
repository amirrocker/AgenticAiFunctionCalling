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
import com.google.ai.edge.litertlm.SamplerConfig
import de.adesso.agenticaifunctioncalling.data.parser.AGENT_SYSTEM_PROMPT
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

    /**
     * Loads the model and creates the Conversation.
     * Must be called on a non-main thread (Dispatchers.IO recommended).
     */
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
            cacheDir = if (modelPath.startsWith("/data/local/tmp"))
                context.getExternalFilesDir(null)?.absolutePath
            else null,
        )
        engine = Engine(config).apply { initialize() }

        // setup tool
        val configWithTool = ConversationConfig(
            systemInstruction = Contents.of(AGENT_SYSTEM_PROMPT),
            samplerConfig = SamplerConfig(
                temperature = 0.1,     // low → reliable tag formatting
                topK = 40,
                topP = 0.95
            )
        )

        // create single session (only one supported at a time)
        conversation = engine?.createConversation(configWithTool)
        Log.d(TAG, "Conversation ready.")
    }

    /**
     * Sends [userMessage] and returns a cold [Flow] that emits partial text
     * tokens as the model streams them.
     *
     * Raw output may contain <function_call> tags; callers pass it through
     * [FunctionCallParser] after the stream completes.
     */
    fun chat(userMessage: String): Flow<String> {
        val conv = requireNotNull(conversation) {
            "Engine not initialized – call initialize() first."
        }
        return conv.sendMessageAsync(userMessage).map { message: Message ->
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
}
