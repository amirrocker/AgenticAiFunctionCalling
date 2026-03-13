package de.adesso.agenticaifunctioncalling.ui.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.adesso.agenticaifunctioncalling.model.ChatMessage
import de.adesso.agenticaifunctioncalling.model.FunctionResult
import de.adesso.agenticaifunctioncalling.model.ModelState
import de.adesso.agenticaifunctioncalling.model.Role
import de.adesso.agenticaifunctioncalling.ui.DownloadOverlay
import de.adesso.agenticaifunctioncalling.ui.GreenPrimary
import de.adesso.agenticaifunctioncalling.ui.Muted
import de.adesso.agenticaifunctioncalling.ui.OnSurfaceText
import de.adesso.agenticaifunctioncalling.ui.SurfaceBot
import de.adesso.agenticaifunctioncalling.ui.SurfaceTool
import de.adesso.agenticaifunctioncalling.ui.SurfaceUser
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
fun AgentScreen(
    viewModel: AgentViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = Color(0xFF0D1B2A),
        topBar = { AgentTopBar() },
        bottomBar = {
            InputBar(
                text       = uiState.inputText,
                enabled    = uiState.modelState is ModelState.Ready && !uiState.isThinking,
                onTextChange = viewModel::onInputChange,
                onSend     = viewModel::sendMessage
            )
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val state = uiState.modelState) {
                is ModelState.Absent ->
                    StatusOverlay("Prüfe Modell…")

                is ModelState.Downloading ->
                    DownloadOverlay(state)

                is ModelState.Error ->
                    StatusOverlay("Fehler: ${state.message}", isError = true)

                is ModelState.Ready ->
                    MessageList(
                        messages   = uiState.messages,
                        isThinking = uiState.isThinking
                    )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentTopBar() {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SmartToy,
                    contentDescription = null,
                    tint = GreenPrimary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Android Agent",
                    color = OnSurfaceText,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "LiteRT-LM · Gemma 3 1B",
                    fontSize = 11.sp,
                    color = Muted
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF112233)
        )
    )
}

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    isThinking: Boolean
) {
    val listState = rememberLazyListState()
    val scope     = rememberCoroutineScope()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty())
            scope.launch { listState.animateScrollToItem(messages.lastIndex) }
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(messages, key = { it.hashCode() }) { msg ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + slideInVertically { it / 2 }
            ) {
                MessageBubble(msg)
            }
        }

        if (isThinking && (messages.isEmpty() || !messages.last().isStreaming)) {
            item { ThinkingIndicator() }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == Role.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = if (isUser) "Du" else "Assistent",
            fontSize = 11.sp,
            color = if (isUser) GreenPrimary else Muted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        if (msg.text.isNotBlank()) {
            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isUser) 16.dp else 4.dp,
                    topEnd = if (isUser) 4.dp else 16.dp,
                    bottomStart = 16.dp,
                    bottomEnd = 16.dp
                ),
                color = if (isUser) SurfaceUser else SurfaceBot,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Text(
                    text = msg.text,
                    color = OnSurfaceText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (msg.isStreaming) StreamingCursor()

        msg.functionCall?.let { call ->
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceTool,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        text = "⚙ ${call.name}",
                        color = GreenPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    call.args.forEach { (k, v) ->
                        Text(
                            text = "  $k: $v",
                            color = Muted,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        msg.functionResult?.let { result ->
            Spacer(Modifier.height(3.dp))
            val (icon, text, tint) = when (result) {
                is FunctionResult.Success -> Triple(Icons.Filled.CheckCircle, result.message, GreenPrimary)
                is FunctionResult.Failure -> Triple(Icons.Filled.Error, result.reason, Color(0xFFFF6B6B))
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.widthIn(max = 300.dp)
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(text, color = tint, fontSize = 12.sp, fontStyle = FontStyle.Italic)
            }
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
    ) {
        repeat(3) { index ->
            val inf = rememberInfiniteTransition(label = "dot$index")
            val alpha by inf.animateFloat(
                initialValue = 0.2f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(index * 160)
                ),
                label = "alpha$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(GreenPrimary)
                    .alpha(alpha)
            )
        }
    }
}

@Composable
private fun StreamingCursor() {
    val inf = rememberInfiniteTransition(label = "cursor")
    val alpha by inf.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Box(
        modifier = Modifier
            .padding(top = 3.dp)
            .size(width = 2.dp, height = 14.dp)
            .background(GreenPrimary.copy(alpha = alpha))
    )
}

@Composable
private fun StatusOverlay(message: String, isError: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            color = if (isError) Color(0xFFFF6B6B) else Muted,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun InputBar(
    text: String,
    enabled: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(
        color = Color(0xFF112233),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                placeholder = { Text("Schreib etwas…", color = Muted, fontSize = 14.sp) },
                enabled = enabled,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = OnSurfaceText,
                    unfocusedTextColor = OnSurfaceText,
                    focusedBorderColor = GreenPrimary,
                    unfocusedBorderColor = Color(0xFF2A3F55),
                    cursorColor = GreenPrimary,
                    disabledBorderColor = Color(0xFF1E2F40)
                ),
                maxLines = 4
            )

            Spacer(Modifier.width(8.dp))

            FilledIconButton(
                onClick = onSend,
                enabled = enabled && text.isNotBlank(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = GreenPrimary,
                    disabledContainerColor = Color(0xFF1E4A2E)
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Senden",
                    tint = Color(0xFF0D1B2A)
                )
            }
        }
    }
}