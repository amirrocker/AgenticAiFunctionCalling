package de.adesso.agenticaifunctioncalling.ui.agent

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.adesso.agenticaifunctioncalling.data.dispatcher.FunctionDispatcher
import de.adesso.agenticaifunctioncalling.data.engine.LiteRtEngine
import de.adesso.agenticaifunctioncalling.data.parser.FunctionCallParser
import de.adesso.agenticaifunctioncalling.data.repository.ModelDownloadException
import de.adesso.agenticaifunctioncalling.data.repository.ModelRepository
import de.adesso.agenticaifunctioncalling.model.ChatMessage
import de.adesso.agenticaifunctioncalling.model.FunctionCall
import de.adesso.agenticaifunctioncalling.model.FunctionResult
import de.adesso.agenticaifunctioncalling.model.ModelState
import de.adesso.agenticaifunctioncalling.model.Role
import de.adesso.agenticaifunctioncalling.navigation.NavigationDestination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "AgentViewModel"

class AgentViewModel(
    private val engine: LiteRtEngine,
    private val dispatcher: FunctionDispatcher,
    private val repository: ModelRepository,
    context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentUiState())
    val uiState: StateFlow<AgentUiState> = _uiState.asStateFlow()

    init {
        checkOrDownloadModel(context)
    }

    private fun checkOrDownloadModel(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            launch {
                repository.downloadState.collect { state ->
                    _uiState.update { it.copy(modelState = state) }
                }
            }

            try {
                repository.ensureModelReady() // suspends here — engine never called until this returns
                engine.initialize(context) // only reached when file is fully validated on disk
                _uiState.update { it.copy(modelState = ModelState.Ready) }
            } catch (e: ModelDownloadException) {
                _uiState.update { it.copy(modelState = ModelState.Error(e.message ?: "Fehler")) }
            }
        }
    }

    fun navigateTo(destination: NavigationDestination) {
        _uiState.update { it.copy(currentDestination = destination) }
    }

    fun onInputChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isThinking) return
        _uiState.update { state ->
            state.copy(
                inputText = "",
                isThinking = true,
                messages = state.messages + ChatMessage(Role.USER, text)
            )
        }
        viewModelScope.launch { streamResponse(text) }
    }

    private suspend fun streamResponse(userText: String) {
        val placeholderIndex = _uiState.value.messages.size
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(
                    role = Role.ASSISTANT, text = "", isStreaming = true
                )
            )
        }

        var fullRaw = ""
        engine.chat(userText).collect { token ->
            fullRaw += token
            val displayText = FunctionCallParser.stripTags(fullRaw)
            _uiState.update { state ->
                val updated = state.messages.toMutableList().also {
                    it[placeholderIndex] = ChatMessage(
                        role = Role.ASSISTANT, text = displayText, isStreaming = true
                    )
                }
                state.copy(messages = updated)
            }
        }

        Log.d(TAG, "Full raw output: $fullRaw")

        val call = FunctionCallParser.parse(fullRaw)
        val displayText = FunctionCallParser.stripTags(fullRaw)

        Log.d(TAG, "Parsed call: $call")

        val result: FunctionResult? = when {

            // ── Path 1: model emitted a <function_call> tag ───────────────────
            call != null -> {
                val navDest = resolveNavigation(call)
                if (navDest != null) {
                    Log.d(TAG, "Tag-based navigation → $navDest")
                    navigateTo(navDest)
                    FunctionResult.Navigation(navDest.label)
                } else {
                    withContext(Dispatchers.IO) { dispatcher.dispatch(call) }
                }
            }

            // ── Path 2: no tag found – infer intent from plain text ───────────
            // Small models (Gemma 3 1B) sometimes ignore the system-prompt tag
            // requirement and just write a sentence like "Ich öffne die Einlagen".
            // We scan the final text for navigation keywords as a last resort.
            else -> {
                val inferred = inferNavigationFromText(displayText, userText)
                if (inferred != null) {
                    Log.d(TAG, "Text-based navigation fallback → $inferred")
                    navigateTo(inferred)
                    FunctionResult.Navigation("${inferred.label} (erkannt aus Text)")
                } else {
                    Log.d(TAG, "No navigation intent detected.")
                    null
                }
            }
        }

        _uiState.update { state ->
            val updated = state.messages.toMutableList().also {
                it[placeholderIndex] = ChatMessage(
                    role = Role.ASSISTANT,
                    text = displayText,
                    functionCall = call,
                    functionResult = result,
                    isStreaming = false
                )
            }
            state.copy(messages = updated, isThinking = false)
        }
    }

    override fun onCleared() {
        super.onCleared()
        engine.close()
    }
}

data class AgentUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
    val modelState: ModelState = ModelState.Absent,
    val inputText: String = "",
    val currentDestination: NavigationDestination = NavigationDestination.Chat
)

/**
 * Maps a parsed [FunctionCall] to a [NavigationDestination].
 * Handles the canonical navigateTo+args format AND shorthand names a small
 * model might emit (showDeposits, navigate_to_deposits, openContracts, …).
 */
private fun resolveNavigation(call: FunctionCall): NavigationDestination? {
    val name = call.name.lowercase()
    val dest = call.args["destination"]?.lowercase()
    val city = call.args["city"]

    // Canonical: navigateTo + destination arg
    if (name == "navigateto" && dest != null) {
        return dest.toNavDestination(city)
    }

    return when {
        name.containsAny("deposit") -> NavigationDestination.Deposits
        name.containsAny("contract") -> NavigationDestination.Contracts
        name.containsAny("reloc") -> NavigationDestination.Relocation(city)
        name.containsAny("chat", "home", "main", "back") -> NavigationDestination.Chat
        dest != null -> dest.toNavDestination(city)
        else -> null
    }
}

/**
 * Last-resort intent detection: scans the assistant's plain-text reply AND
 * the original user message for navigation keywords.
 *
 * Only triggers when the model produced no <function_call> tag at all.
 * Deliberately conservative — only fires on clear, unambiguous signals.
 */
private fun inferNavigationFromText(
    assistantText: String,
    userText: String
): NavigationDestination? {
    val combined = ("$assistantText $userText").lowercase()

    return when {
        combined.containsAny("einlagen", "deposits", "guthaben", "kontostand") ->
            NavigationDestination.Deposits

        combined.containsAny("vertrag", "verträge", "contracts", "vereinbarung") ->
            NavigationDestination.Contracts

        combined.containsAny("umzug", "relocation", "relocat", "umziehen", "ziehe") ->
            NavigationDestination.Relocation()

        combined.containsAny("startseite", "chat", "zurück", "home") ->
            NavigationDestination.Chat

        else -> null
    }
}

private fun String.toNavDestination(city: String? = null): NavigationDestination? =
    when (this.trim()) {
        "chat", "home", "main" -> NavigationDestination.Chat
        "deposits", "deposit" -> NavigationDestination.Deposits
        "relocation", "reloc" -> NavigationDestination.Relocation(city)
        "contracts", "contract" -> NavigationDestination.Contracts
        else -> null
    }

private fun String.containsAny(vararg keywords: String) =
    keywords.any { this.contains(it, ignoreCase = true) }

private val NavigationDestination.label: String
    get() = when (this) {
        is NavigationDestination.Chat -> "Chat"
        is NavigationDestination.Deposits -> "Deposits"
        is NavigationDestination.Relocation -> "Relocation${city?.let { " – $it" } ?: ""}"
        is NavigationDestination.Contracts -> "Contracts"
    }