package org.sosnet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.sosnet.R
import org.sosnet.mesh.MessageEntity

@Composable
fun MessagesScreen(messages: List<MessageEntity>) {
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.received_messages),
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFE6E8F0),
        )
        Text(
            stringResource(R.string.chat_coming_soon),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9AA0B4),
        )
        if (messages.isEmpty()) {
            Text(
                stringResource(R.string.empty_inbox),
                color = Color(0xFF9AA0B4),
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(messages, key = { it.id }) { msg ->
                    MessageBubble(msg)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: MessageEntity) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF161C2C))
            .padding(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${stringResource(R.string.channel_sos)} • ${msg.sosType}",
                color = Color(0xFF7C5CFF),
                style = MaterialTheme.typography.labelMedium,
            )
            Text("rssi ${msg.rssi}", color = Color(0xFF9AA0B4), style = MaterialTheme.typography.labelSmall)
        }
        Text(
            msg.bodyText ?: "(${msg.latE7 / 1e7}, ${msg.lonE7 / 1e7})",
            color = Color(0xFFE6E8F0),
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            msg.nodeId.toHex().take(16),
            color = Color(0xFF9AA0B4),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
