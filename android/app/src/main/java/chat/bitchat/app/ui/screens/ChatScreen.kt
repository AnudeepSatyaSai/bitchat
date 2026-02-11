// This is free and unencumbered software released into the public domain.
// For more information, see <https://unlicense.org>

package chat.bitchat.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.bitchat.app.model.BitchatMessage
import chat.bitchat.app.model.DeliveryStatus
import chat.bitchat.app.transport.TransportPeerSnapshot
import chat.bitchat.app.transport.TransportState
import chat.bitchat.app.ui.theme.BitchatColors
import chat.bitchat.app.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main chat screen — Jetpack Compose.
 * Port of Swift's ChatView.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val messages by viewModel.messages.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val nickname by viewModel.nickname.collectAsState()
    val activeTransports by viewModel.activeTransports.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    var showPeers by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ChatTopBar(
                peerCount = peers.size,
                connectionState = connectionState,
                activeTransports = activeTransports,
                onSettingsClick = { showSettings = true },
                onPeersClick = { showPeers = !showPeers }
            )
        },
        bottomBar = {
            MessageInput(
                onSend = { viewModel.sendMessage(it) }
            )
        },
        containerColor = BitchatColors.Background
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // Peer list (expandable)
            AnimatedVisibility(
                visible = showPeers,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                PeerListBanner(peers = peers)
            }

            // Message list
            MessageList(
                messages = messages,
                myNickname = nickname,
                modifier = Modifier.weight(1f)
            )
        }
    }

    // Settings sheet
    if (showSettings) {
        SettingsSheet(
            nickname = nickname,
            fingerprint = viewModel.getDeviceFingerprint(),
            onNicknameChange = { viewModel.updateNickname(it) },
            onDismiss = { showSettings = false }
        )
    }
}

// ============ Top Bar ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTopBar(
    peerCount: Int,
    connectionState: TransportState,
    activeTransports: List<String>,
    onSettingsClick: () -> Unit,
    onPeersClick: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "BitChat",
                    style = TextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = BitchatColors.TextPrimary
                    )
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Status indicator
                    val statusColor = when (connectionState) {
                        TransportState.POWERED_ON -> BitchatColors.Online
                        TransportState.POWERED_OFF -> BitchatColors.Offline
                        TransportState.UNAUTHORIZED -> BitchatColors.Warning
                        else -> BitchatColors.TextMuted
                    }

                    // Pulsing dot
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.5f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "pulseAlpha"
                    )

                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = alpha))
                    )

                    Text(
                        text = if (peerCount > 0) "$peerCount peer${if (peerCount != 1) "s" else ""}"
                        else "Scanning...",
                        style = TextStyle(
                            fontSize = 12.sp,
                            color = BitchatColors.TextMuted
                        )
                    )

                    // Transport tags
                    for (transport in activeTransports) {
                        val (label, color) = when (transport) {
                            "ble" -> "BLE" to BitchatColors.TransportBLE
                            "wifi-aware" -> "WiFi" to BitchatColors.TransportWiFiAware
                            else -> transport to BitchatColors.TextMuted
                        }
                        Text(
                            text = label,
                            modifier = Modifier
                                .background(
                                    color.copy(alpha = 0.2f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp),
                            style = TextStyle(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = color
                            )
                        )
                    }
                }
            }
        },
        actions = {
            IconButton(onClick = onPeersClick) {
                Icon(
                    Icons.Default.People,
                    contentDescription = "Peers",
                    tint = BitchatColors.TextSecondary
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = BitchatColors.TextSecondary
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = BitchatColors.Surface
        )
    )
}

// ============ Peer List Banner ============

@Composable
private fun PeerListBanner(peers: List<TransportPeerSnapshot>) {
    if (peers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BitchatColors.SurfaceElevated)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No peers nearby. Move closer to other BitChat devices.",
                style = TextStyle(fontSize = 13.sp, color = BitchatColors.TextMuted)
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BitchatColors.SurfaceElevated)
                .padding(12.dp)
        ) {
            Text(
                text = "NEARBY PEERS",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = BitchatColors.TextMuted,
                    letterSpacing = 1.sp
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            for (peer in peers) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                if (peer.isConnected) BitchatColors.Online
                                else BitchatColors.Offline
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = peer.nickname.ifEmpty { peer.peerID.id.take(8) },
                        style = TextStyle(fontSize = 14.sp, color = BitchatColors.TextPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = peer.peerID.id.take(8),
                        style = TextStyle(fontSize = 11.sp, color = BitchatColors.TextMuted)
                    )
                }
            }
        }
    }
}

// ============ Message List ============

@Composable
private fun MessageList(
    messages: List<BitchatMessage>,
    myNickname: String,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    if (messages.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "⚡",
                    fontSize = 48.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No messages yet",
                    style = TextStyle(
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = BitchatColors.TextSecondary
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Messages are end-to-end encrypted\nand never touch a server.",
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = BitchatColors.TextMuted,
                        lineHeight = 20.sp
                    )
                )
            }
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                val isMine = message.sender == myNickname
                MessageBubble(message = message, isMine = isMine)
            }
        }
    }
}

// ============ Message Bubble ============

@Composable
private fun MessageBubble(message: BitchatMessage, isMine: Boolean) {
    val isPrivate = message.isPrivate

    val bubbleColor = when {
        isPrivate && isMine -> BitchatColors.BubblePrivateSent
        isPrivate -> BitchatColors.BubblePrivateReceived
        isMine -> BitchatColors.BubbleSent
        else -> BitchatColors.BubbleReceived
    }

    val alignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    val shape = if (isMine) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(shape)
                .background(bubbleColor)
                .border(
                    width = 1.dp,
                    color = BitchatColors.GlassBorder,
                    shape = shape
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Sender name (for received messages)
            if (!isMine) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = message.sender,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = BitchatColors.Accent
                        )
                    )
                    if (isPrivate) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = "Private",
                            modifier = Modifier.size(12.dp),
                            tint = BitchatColors.Warning
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
            }

            // Message content
            Text(
                text = message.content,
                style = TextStyle(
                    fontSize = 15.sp,
                    color = BitchatColors.TextPrimary,
                    lineHeight = 20.sp
                )
            )

            // Timestamp + status
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatTime(message.timestamp),
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = BitchatColors.TextMuted
                    )
                )
                if (isMine) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DeliveryStatusIcon(status = message.deliveryStatus)
                }
            }
        }
    }
}

@Composable
private fun DeliveryStatusIcon(status: DeliveryStatus?) {
    val (icon, tint) = when (status) {
        is DeliveryStatus.Sending -> Icons.Default.Schedule to BitchatColors.TextMuted
        is DeliveryStatus.Sent -> Icons.Default.Done to BitchatColors.TextMuted
        is DeliveryStatus.Delivered -> Icons.Default.DoneAll to BitchatColors.TextSecondary
        is DeliveryStatus.Read -> Icons.Default.DoneAll to BitchatColors.Accent
        is DeliveryStatus.Failed -> Icons.Default.ErrorOutline to BitchatColors.Error
        null -> return
    }

    Icon(
        imageVector = icon,
        contentDescription = status?.displayText ?: "",
        modifier = Modifier.size(14.dp),
        tint = tint
    )
}

// ============ Message Input ============

@Composable
private fun MessageInput(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BitchatColors.Surface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Text field
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(24.dp))
                .background(BitchatColors.SurfaceLight)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (text.isEmpty()) {
                Text(
                    text = "Message...",
                    style = TextStyle(fontSize = 15.sp, color = BitchatColors.TextMuted)
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(
                    fontSize = 15.sp,
                    color = BitchatColors.TextPrimary
                ),
                cursorBrush = SolidColor(BitchatColors.Primary),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
                maxLines = 4
            )
        }

        // Send button
        val canSend = text.isNotBlank()
        val sendButtonColor by animateColorAsState(
            targetValue = if (canSend) BitchatColors.Primary else BitchatColors.SurfaceLight,
            label = "sendColor"
        )

        IconButton(
            onClick = {
                if (canSend) {
                    onSend(text)
                    text = ""
                }
            },
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(sendButtonColor)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (canSend) Color.White else BitchatColors.TextMuted,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ============ Settings Sheet ============

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    nickname: String,
    fingerprint: String,
    onNicknameChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editingNickname by remember { mutableStateOf(nickname) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BitchatColors.Surface,
        contentColor = BitchatColors.TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            Text(
                text = "Settings",
                style = TextStyle(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = BitchatColors.TextPrimary
                )
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Nickname
            Text(
                text = "NICKNAME",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = BitchatColors.TextMuted,
                    letterSpacing = 1.sp
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = editingNickname,
                onValueChange = { editingNickname = it.take(32) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BitchatColors.Primary,
                    unfocusedBorderColor = BitchatColors.SurfaceLight,
                    cursorColor = BitchatColors.Primary,
                    focusedTextColor = BitchatColors.TextPrimary,
                    unfocusedTextColor = BitchatColors.TextPrimary,
                ),
                trailingIcon = {
                    if (editingNickname != nickname) {
                        IconButton(onClick = {
                            onNicknameChange(editingNickname)
                        }) {
                            Icon(
                                Icons.Default.Check,
                                "Save",
                                tint = BitchatColors.Success
                            )
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Fingerprint
            Text(
                text = "DEVICE FINGERPRINT",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = BitchatColors.TextMuted,
                    letterSpacing = 1.sp
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = fingerprint,
                style = TextStyle(
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    color = BitchatColors.Accent,
                    letterSpacing = 2.sp
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Share this with peers to verify identity",
                style = TextStyle(fontSize = 12.sp, color = BitchatColors.TextMuted)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Transport info
            Text(
                text = "TRANSPORTS",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = BitchatColors.TextMuted,
                    letterSpacing = 1.sp
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TransportChip("BLE", BitchatColors.TransportBLE, true)
                TransportChip("Wi-Fi Aware", BitchatColors.TransportWiFiAware, true)
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TransportChip(label: String, color: Color, active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (active) color else BitchatColors.Offline)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = color
            )
        )
    }
}

// ============ Utilities ============

private fun formatTime(date: Date): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(date)
}
