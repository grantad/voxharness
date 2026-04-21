package com.voxharness.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.voxharness.app.service.ChatMessage
import com.voxharness.app.service.ConversationEngine
import com.voxharness.app.service.Status
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var engine: ConversationEngine

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) engine.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        engine = ConversationEngine(this)
        engine.intentLauncher = { intent -> startActivity(intent) }

        setContent {
            VoxHarnessTheme {
                VoxHarnessScreen(engine)
            }
        }

        // Request mic permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            engine.start()
        } else {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.stop()
    }
}

@Composable
fun VoxHarnessTheme(content: @Composable () -> Unit) {
    val darkColors = darkColorScheme(
        primary = Color(0xFF6C5CE7),
        background = Color(0xFF0A0A0F),
        surface = Color(0xFF141420),
        onBackground = Color(0xFFE0E0E8),
        onSurface = Color(0xFFE0E0E8),
    )
    MaterialTheme(colorScheme = darkColors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoxHarnessScreen(engine: ConversationEngine) {
    val state by engine.state.collectAsState()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var textInput by remember { mutableStateOf("") }

    // Auto-scroll to bottom
    LaunchedEffect(state.messages.size, state.currentResponse) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VoxHarness", fontWeight = FontWeight.Bold) },
                actions = {
                    StatusIndicator(state.status)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Messages
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.messages) { msg ->
                    MessageBubble(msg)
                }

                // Show streaming response
                if (state.currentResponse.isNotEmpty()) {
                    item {
                        MessageBubble(ChatMessage("assistant", state.currentResponse))
                    }
                }
            }

            // Status text
            if (true) {
                Text(
                    text = when (state.status) {
                        Status.WAITING_FOR_WAKE_WORD -> "Say \"Computer\" to start..."
                        Status.LISTENING -> "Listening..."
                        Status.TRANSCRIBING -> "Transcribing..."
                        Status.THINKING -> "Thinking..."
                        Status.SPEAKING -> "Speaking..."
                    },
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Push to talk button
                Button(
                    onClick = { },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.status == Status.LISTENING)
                            Color(0xFFFF5252) else MaterialTheme.colorScheme.primary
                    ),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("🎤", fontSize = 20.sp)
                }

                // Text input
                OutlinedTextField(
                    value = textInput,
                    onValueChange = { textInput = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message...") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    ),
                )

                // Send
                Button(
                    onClick = {
                        if (textInput.isNotBlank()) {
                            engine.sendTextInput(textInput.trim())
                            textInput = ""
                        }
                    },
                    enabled = textInput.isNotBlank(),
                ) {
                    Text("Send")
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 12.dp,
            ),
            color = if (isUser) Color(0xFF2D2D44) else Color(0xFF1A1A2E),
            border = if (!isUser) androidx.compose.foundation.BorderStroke(
                1.dp, Color(0xFF2A2A3E)
            ) else null,
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isUser) "YOU" else "ASSISTANT",
                    fontSize = 10.sp,
                    color = Color(0xFF8888A0),
                    letterSpacing = 0.5.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.content,
                    color = Color(0xFFE0E0E8),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }
    }
}

@Composable
fun StatusIndicator(status: Status) {
    val color = when (status) {
        Status.WAITING_FOR_WAKE_WORD -> Color(0xFF8888A0)
        Status.LISTENING -> Color(0xFF6C5CE7)
        Status.TRANSCRIBING -> Color(0xFFFFAB40)
        Status.THINKING -> Color(0xFFFFAB40)
        Status.SPEAKING -> Color(0xFF00E676)
    }

    Box(
        modifier = Modifier
            .padding(end = 16.dp)
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}
