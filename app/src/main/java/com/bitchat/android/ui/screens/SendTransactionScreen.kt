package com.bitchat.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.viewmodels.SendTransactionViewModel
import com.bitchat.android.viewmodels.SendTransactionViewModel.SendState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendTransactionScreen(
    viewModel: SendTransactionViewModel,
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    onSelectPeer: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recipient by viewModel.recipient.observeAsState("")
    var amountSol by remember { mutableStateOf("") }
    var priorityFee by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    val sendState by viewModel.sendState.observeAsState(SendState.Idle)

    when (val state = sendState) {
        is SendState.Success -> {
            showConfirm = false
            viewModel.resetState()
            onSuccess()
            return
        }
        is SendState.Error -> {
            if (showConfirm) {
                showConfirm = false
                viewModel.resetState()
            }
        }
        else -> {}
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.solana_send_title)) },
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
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = recipient,
                onValueChange = { viewModel.setRecipient(it) },
                label = { Text(stringResource(R.string.solana_recipient)) },
                placeholder = { Text(stringResource(R.string.solana_recipient_hint)) },
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                onClick = onSelectPeer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.solana_select_peer))
            }
            OutlinedTextField(
                value = amountSol,
                onValueChange = { amountSol = it.filter { c -> c.isDigit() || c == '.' } },
                label = { Text(stringResource(R.string.solana_amount)) },
                placeholder = { Text(stringResource(R.string.solana_amount_hint)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth()
            )
            RowWithLabel(
                label = stringResource(R.string.solana_priority_fee),
                content = {
                    Switch(checked = priorityFee, onCheckedChange = { priorityFee = it })
                }
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val lamports = (amountSol.toDoubleOrNull() ?: 0.0).times(1_000_000_000).toLong()
                    if (lamports > 0 && recipient.isNotBlank()) {
                        viewModel.prepareSend(recipient.trim(), lamports, priorityFee)
                        showConfirm = true
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.solana_review_confirm))
            }
            when (val s = sendState) {
                is SendState.Error -> Text(
                    s.message,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.error
                )
                is SendState.ReadyToConfirm -> { /* dialog below */ }
                is SendState.Signing -> Text(stringResource(R.string.solana_sending))
                else -> {}
            }
        }
    }

    if (showConfirm && sendState is SendState.ReadyToConfirm) {
        val s = sendState as SendState.ReadyToConfirm
        ConfirmSendDialog(
            recipient = s.recipient,
            amountLamports = s.amountLamports,
            onConfirm = { viewModel.confirmAndSign() },
            onDismiss = { showConfirm = false; viewModel.resetState() }
        )
    }
}

@Composable
private fun RowWithLabel(
    label: String,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label)
        content()
    }
}

@Composable
private fun ConfirmSendDialog(
    recipient: String,
    amountLamports: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.solana_confirm_send)) },
        text = {
            Column {
                Text("To: $recipient")
                Text("Amount: ${amountLamports / 1_000_000_000.0} SOL")
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        }
    )
}
