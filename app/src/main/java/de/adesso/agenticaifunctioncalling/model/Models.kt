package de.adesso.agenticaifunctioncalling.model

import kotlinx.serialization.Serializable

/**
 * A parsed function call extracted from raw LLM output.
 * The LLM writes JSON inside <function_call>…</function_call> tags;
 * this class is the typed result of parsing that JSON.
 */
@Serializable
data class FunctionCall(
    val name: String,
    val args: Map<String, String> = emptyMap()
)

/**
 * Result after a call has been executed.
 *
 * [Navigation] is a lightweight variant used when the agent triggered an
 * in-app navigation rather than a system action.  It shows a small chip
 * in the chat bubble confirming where the user was taken.
 */
sealed class FunctionResult {
    data class Success(val message: String) : FunctionResult()
    data class Failure(val reason: String) : FunctionResult()
    data class Navigation(val destination: String) : FunctionResult()
}

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val role: Role,
    val text: String,
    val functionCall: FunctionCall? = null,
    val functionResult: FunctionResult? = null,
    val isStreaming: Boolean = false
)

data class DownloadProgress(
    val bytesReceived: Long,
    val totalBytes: Long
) {
    val percent: Int
        get() =
            if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt() else 0
}

sealed class ModelState {
    data object Absent : ModelState()
    data class Downloading(val progress: DownloadProgress) : ModelState()
    data object Ready : ModelState()
    data class Error(val message: String) : ModelState()
}
