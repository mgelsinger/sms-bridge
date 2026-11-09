package io.github.mgelsinger.smsbridge.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mgelsinger.smsbridge.core.PairingManager
import io.github.mgelsinger.smsbridge.platform.AppGraph
import io.github.mgelsinger.smsbridge.platform.BridgeService
import io.github.mgelsinger.smsbridge.platform.EventLog
import io.github.mgelsinger.smsbridge.platform.ServiceState
import io.github.mgelsinger.smsbridge.protocol.PROTOCOL_VERSION
import io.github.mgelsinger.smsbridge.protocol.PairingQr
import io.github.mgelsinger.smsbridge.protocol.ProtocolJson
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

private val REQUIRED = buildList {
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.READ_CONTACTS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun missing(context: Context) = REQUIRED.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

@Composable
fun SettingsScreen(graph: AppGraph) {
    val context = LocalContext.current
    val state by graph.state.collectAsStateWithLifecycle()
    val clients by graph.pairing.clients.collectAsStateWithLifecycle()
    val session by graph.pairing.session.collectAsStateWithLifecycle()
    val pending by graph.confirmations.pending.collectAsStateWithLifecycle()
    val log by graph.log.lines.collectAsStateWithLifecycle()
    var refresh by remember { mutableIntStateOf(0) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val missingNow = remember(refresh) { missing(context) }
    val batteryExempt = remember(refresh) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("SMS Bridge", style = MaterialTheme.typography.headlineMedium) }
        item { StatusCard(graph, state) }
        if (missingNow.isNotEmpty() || !batteryExempt) {
            item {
                SetupCard(
                    missing = missingNow,
                    batteryExempt = batteryExempt,
                    onGrant = { permissions.launch(missingNow.toTypedArray()) },
                    onBattery = {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                        )
                        refresh++
                    },
                    onAppInfo = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    },
                )
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paired PCs", style = MaterialTheme.typography.titleMedium)
                    if (clients.isEmpty()) Text("None yet.")
                    for (c in clients) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.name)
                                Text(
                                    "Paired ${date(c.pairedAt)}" + (c.lastSeen?.let { " · last seen ${date(it)}" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { graph.revoke(c) }) { Text("Revoke") }
                        }
                    }
                    Button(
                        onClick = { graph.pairing.startSession() },
                        enabled = state is ServiceState.Running,
                    ) { Text("Pair a PC") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Recent events", style = MaterialTheme.typography.titleMedium)
                    Text("Message text is never logged.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(log.take(50)) { Text(EventLog.format(it), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
        item { Text("Protocol version $PROTOCOL_VERSION", style = MaterialTheme.typography.bodySmall) }
    }

    val running = state as? ServiceState.Running
    val s = session
    if (s != null && running != null) PairDialog(graph, running, s)

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { graph.confirmations.answer(false) },
            title = { Text("Pair with \"${p.clientName}\"?") },
            text = { Text("This PC will be able to read your texts and send texts from your number while it is on your Wi-Fi.") },
            confirmButton = { Button(onClick = { graph.confirmations.answer(true) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { graph.confirmations.answer(false) }) { Text("Decline") } },
        )
    }
}

@Composable
private fun StatusCard(graph: AppGraph, state: ServiceState) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                when (state) {
                    ServiceState.Stopped -> "Off"
                    ServiceState.WaitingForWifi -> "On, waiting for Wi-Fi"
                    is ServiceState.Running -> "On at ${state.host}:${state.port}"
                    is ServiceState.Failed -> "Could not start: ${state.reason}"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            if (state == ServiceState.Stopped) {
                Button(onClick = { BridgeService.start(context) }) { Text("Turn on") }
            } else {
                OutlinedButton(onClick = { BridgeService.stop(context) }) { Text("Turn off") }
            }
            if (state !is ServiceState.Stopped) {
                Text("Certificate fingerprint", style = MaterialTheme.typography.bodySmall)
                Text(groupFingerprint(graph.identity.fingerprint), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SetupCard(
    missing: List<String>,
    batteryExempt: Boolean,
    onGrant: () -> Unit,
    onBattery: () -> Unit,
    onAppInfo: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium)
            if (missing.isNotEmpty()) {
                Text("Needs: " + missing.joinToString { it.substringAfterLast('.').lowercase().replace('_', ' ') })
                Button(onClick = onGrant) { Text("Grant permissions") }
                Text(
                    "If Android refuses the SMS permission, open App info, tap the three-dot menu, choose " +
                        "\"Allow restricted settings\", then come back and grant again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onAppInfo) { Text("Open App info") }
            }
            if (!batteryExempt) {
                Text("Let the bridge run in the background so texts reach your PC while the screen is off.")
                OutlinedButton(onClick = onBattery) { Text("Allow background use") }
            }
        }
    }
}

@Composable
private fun PairDialog(graph: AppGraph, running: ServiceState.Running, session: PairingManager.Session) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(session) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= session.expiresAt) {
                graph.pairing.cancelSession()
                break
            }
            delay(1_000)
        }
    }
    val qr = remember(session, running) {
        qrBitmap(
            ProtocolJson.encodeToString(
                PairingQr.serializer(),
                PairingQr(
                    host = running.host,
                    port = running.port,
                    fingerprint = graph.identity.fingerprint,
                    pairingCode = session.code,
                    deviceName = graph.repo.deviceInfo().deviceName,
                ),
            ),
        )
    }
    AlertDialog(
        onDismissRequest = { graph.pairing.cancelSession() },
        title = { Text("Pair a PC") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(qr, contentDescription = "Pairing QR code", modifier = Modifier.size(240.dp))
                Text(session.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
                Text("${running.host}:${running.port}")
                Text("Expires in ${((session.expiresAt - now) / 1000).coerceAtLeast(0)} s", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.size(4.dp))
                Text("Check this matches the PC:", style = MaterialTheme.typography.bodySmall)
                Text(groupFingerprint(graph.identity.fingerprint), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { graph.pairing.cancelSession() }) { Text("Cancel") } },
    )
}

private fun date(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
