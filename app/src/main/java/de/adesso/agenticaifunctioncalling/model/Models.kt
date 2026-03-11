package de.adesso.agenticaifunctioncalling.model

import kotlinx.serialization.Serializable

// ── Function-call protocol ────────────────────────────────────────────────────

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

/** Result after a dispatcher has executed a FunctionCall. */
sealed class FunctionResult {
    data class Success(val message: String) : FunctionResult()
    data class Failure(val reason: String)  : FunctionResult()
}

// ── Chat ──────────────────────────────────────────────────────────────────────

enum class Role { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val role: Role,
    val text: String,
    val functionCall: FunctionCall? = null,
    val functionResult: FunctionResult? = null,
    val isStreaming: Boolean = false
)

// ── Model download ────────────────────────────────────────────────────────────

data class DownloadProgress(
    val bytesReceived: Long,
    val totalBytes: Long
) {
    val percent: Int get() =
        if (totalBytes > 0) ((bytesReceived * 100) / totalBytes).toInt() else 0
}

sealed class ModelState {
    data object Absent    : ModelState()
    data class  Downloading(val progress: DownloadProgress) : ModelState()
    data object Ready     : ModelState()
    data class  Error(val message: String) : ModelState()
}