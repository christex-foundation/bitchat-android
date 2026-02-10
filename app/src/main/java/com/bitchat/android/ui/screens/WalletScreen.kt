package com.bitchat.android.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import com.bitchat.android.R
import com.bitchat.android.viewmodels.WalletState
import com.bitchat.android.viewmodels.WalletViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletScreen(
    viewModel: WalletViewModel,
    onBack: () -> Unit,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    modifier: Modifier = Modifier
) {
    val walletState by viewModel.walletState.observeAsState(WalletState.Loading)
    val balance by viewModel.balanceLamports.observeAsState(null)
    val toast by viewModel.toastMessage.observeAsState(null)
    val exportResult by viewModel.exportSeedResult.observeAsState(null)
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(toast) {
        toast?.let { viewModel.clearToast() }
    }
    LaunchedEffect(exportResult) {
        exportResult?.let { showExportDialog = true }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.solana_wallet)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when (val state = walletState) {
                is WalletState.Loading -> {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("Loading…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                is WalletState.NoWallet -> {
                    Text(
                        stringResource(R.string.solana_error_no_wallet),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Button(
                        onClick = { viewModel.createWallet() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.solana_create_wallet))
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = { /* TODO: import flow - open dialog for paste */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.solana_import_wallet))
                    }
                }
                is WalletState.Error -> {
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                    Button(onClick = { viewModel.loadWalletState() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.retry))
                    }
                }
                is WalletState.Ready -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                stringResource(R.string.solana_balance),
                                style = MaterialTheme.typography.labelMedium
                            )
                            val sol = balance?.let { it / 1_000_000_000.0 } ?: 0.0
                            Text(
                                stringResource(R.string.solana_balance_sol, "%.4f".format(sol)),
                                style = MaterialTheme.typography.headlineMedium,
                                fontFamily = FontFamily.Monospace
                            )
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                IconButton(onClick = { viewModel.refreshBalance() }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.solana_refresh_balance))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text(
                                stringResource(R.string.solana_address),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    state.address,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                val ctx = LocalContext.current
                                IconButton(onClick = {
                                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                                        ?.setPrimaryClip(ClipData.newPlainText("address", state.address))
                                    viewModel.showToast(ctx.getString(R.string.solana_address_copied))
                                }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.solana_copy_address))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = onSend,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.solana_send))
                        }
                        Button(
                            onClick = onReceive,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.solana_receive))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { viewModel.exportSeedPhrase() }) {
                        Text(stringResource(R.string.solana_export_seed))
                    }
                }
            }
        }
    }

    exportResult?.let { result ->
        if (showExportDialog) {
            AlertDialogExportSeed(
                result = result,
                onDismiss = {
                    showExportDialog = false
                    viewModel.clearExportResult()
                }
            )
        }
    }
}

@Composable
private fun AlertDialogExportSeed(
    result: Result<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.solana_export_seed)) },
        text = {
            Column {
                Text(stringResource(R.string.solana_export_seed_warning))
                result.getOrNull()?.let { key ->
                    Spacer(Modifier.height(8.dp))
                    Text(key, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                result.exceptionOrNull()?.let { Text("Error: ${it.message}", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        }
    )
}
