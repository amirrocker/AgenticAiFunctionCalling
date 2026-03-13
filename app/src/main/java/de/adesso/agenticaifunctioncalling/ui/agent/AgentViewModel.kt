package de.adesso.agenticaifunctioncalling.ui.agent

import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.adesso.agenticaifunctioncalling.data.dispatcher.FunctionDispatcher
import de.adesso.agenticaifunctioncalling.data.engine.LiteRtEngine
import de.adesso.agenticaifunctioncalling.data.parser.FunctionCallParser
import de.adesso.agenticaifunctioncalling.data.repository.ModelDownloadException
import de.adesso.agenticaifunctioncalling.data.repository.ModelRepository
import de.adesso.agenticaifunctioncalling.model.ChatMessage
import de.adesso.agenticaifunctioncalling.model.ModelState
import de.adesso.agenticaifunctioncalling.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.toMutableList

data class AgentUiState(
    val messages:    List<ChatMessage> = emptyList(),
    val isThinking:  Boolean = false,
    val modelState: ModelState = ModelState.Absent,
    val inputText:   String = ""
)

class AgentViewModel(
    private val engine: LiteRtEngine,
    private val parser:     FunctionCallParser,
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
                repository.ensureModelReady()  // suspends here — engine never called until this returns
                engine.initialize(context)            // only reached when file is fully validated on disk
                _uiState.update { it.copy(modelState = ModelState.Ready) }
            } catch (e: ModelDownloadException) {
                _uiState.update { it.copy(modelState = ModelState.Error(e.message ?: "Fehler")) }
            }
        }
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

        viewModelScope.launch {
            streamResponse(text)
        }
    }

    private suspend fun streamResponse(userText: String) {
        val placeholderIndex = _uiState.value.messages.size
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(
                    role = Role.ASSISTANT,
                    text = "",
                    isStreaming = true
                )
            )
        }

        var fullRaw = ""

        // Collect token stream from engine
        engine.chat(userText).collect { token ->
            fullRaw += token
            val displayText = FunctionCallParser.stripTags(fullRaw)
            _uiState.update { state ->
                val updated = state.messages.toMutableList().also {
                    it[placeholderIndex] = ChatMessage(
                        role = Role.ASSISTANT,
                        text = displayText,
                        isStreaming = true
                    )
                }
                state.copy(messages = updated)
            }
        }

        // Stream complete – parse and dispatch
        val call = FunctionCallParser.parse(fullRaw)
        val displayText = FunctionCallParser.stripTags(fullRaw)

        val result = call?.let {
            withContext(Dispatchers.IO) { dispatcher.dispatch(it) }
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