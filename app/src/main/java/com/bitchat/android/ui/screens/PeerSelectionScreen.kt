package com.bitchat.android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bitchat.android.R

/**
 * Peer + optional Solana address (mapping TBD: BitChat peer -> address).
 */
data class PeerItem(val peerId: String, val nickname: String?, val solanaAddress: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSelectionScreen(
    peers: List<PeerItem>,
    onBack: () -> Unit,
    onSelectPeer: (PeerItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.solana_select_peer)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (peers.isEmpty()) {
            Text(
                "No peers available. Address mapping TBD.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(padding).padding(16.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(padding),
                content = {
                    items(peers) { peer ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val addr = peer.solanaAddress
                                    if (addr != null) onSelectPeer(peer)
                                }
                                .padding(16.dp)
                        ) {
                            Text(peer.nickname ?: peer.peerId)
                            if (peer.solanaAddress != null) {
                                Text(
                                    peer.solanaAddress,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Text("Solana address TBD", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            )
        }
    }
}
