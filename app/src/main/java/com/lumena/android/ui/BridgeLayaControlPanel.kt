package com.lumena.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lumena.android.agent.local.LayaSystem1Client
import com.lumena.android.agent.local.TermuxBridgeAutoStarter
import com.lumena.android.agent.local.TermuxBridgeClient
import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.settings.LumenaPreferences
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

private const val BRIDGE_INSTALL_COMMIT =
    "8dd084e9c663be212a0996bfa6d9cb0298139331"

@Composable
internal fun BridgeLayaControlPanel(
    bridgeUrl: String,
    onBridgeUrl: (String) -> Unit,
    bridgeToken: String,
    onBridgeToken: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var revealToken by rememberSaveable { mutableStateOf(false) }
    var bridgeStatus by rememberSaveable {
        mutableStateOf("Bridge not checked")
    }
    var layaStatus by rememberSaveable {
        mutableStateOf("Laya not checked")
    }

    fun clientOrNull(): TermuxBridgeClient? {
        val token = LumenaPreferences.normalizeBridgeToken(bridgeToken)
        if (token.isBlank()) return null
        return runCatching {
            TermuxBridgeClient(
                baseUrl = bridgeUrl,
                token = token,
                context = context
            )
        }.getOrNull()
    }

    fun copyText(label: String, text: String) {
        val clipboard =
            context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(label, text)
        )
    }

    fun startBridge() {
        val base = bridgeUrl.trim().trimEnd('/').toHttpUrlOrNull()
        if (base == null) {
            bridgeStatus = "Bridge URL is invalid"
            return
        }

        scope.launch {
            bridgeStatus = "Starting bridge…"
            val result =
                TermuxBridgeAutoStarter.ensureRunning(context, base)
            bridgeStatus =
                if (result.isSuccess) {
                    "Bridge running · tap Self-test"
                } else {
                    "Bridge start failed: " +
                        (result.exceptionOrNull()?.message ?: "unknown")
                }
        }
    }

    fun bridgeSelfTest() {
        val client = clientOrNull()
        if (client == null) {
            bridgeStatus = "Bridge token is empty or invalid"
            return
        }

        scope.launch {
            bridgeStatus = "Checking bridge…"
            val result = runCatching {
                client.execute(ToolRequest(tool = "health"))
            }.getOrElse {
                bridgeStatus = "Bridge error: " + (it.message ?: "unknown")
                return@launch
            }

            bridgeStatus =
                if (result.ok) {
                    result.stdout
                        .lineSequence()
                        .filter { it.isNotBlank() }
                        .take(6)
                        .joinToString(" · ")
                        .ifBlank { "Bridge OK" }
                } else {
                    "Bridge failed: " +
                        (result.error ?: result.errorCode ?: "unknown")
                }
        }
    }

    fun checkLaya() {
        val client = clientOrNull()
        if (client == null) {
            layaStatus = "Bridge token is required first"
            return
        }

        scope.launch {
            layaStatus = "Checking Laya…"
            val result = runCatching {
                LayaSystem1Client(client).status()
            }.getOrElse {
                layaStatus = "Laya error: " + (it.message ?: "unknown")
                return@launch
            }

            layaStatus =
                if (result.ok) {
                    result.stdout.take(900).ifBlank { "Laya OK" }
                } else {
                    "Laya unavailable: " +
                        (result.error ?: result.errorCode ?: "unknown")
                }
        }
    }

    fun startLaya() {
        val client = clientOrNull()
        if (client == null) {
            layaStatus = "Bridge token is required first"
            return
        }

        scope.launch {
            layaStatus = "Starting Laya…"
            val result = runCatching {
                LayaSystem1Client(client).start()
            }.getOrElse {
                layaStatus = "Laya start error: " +
                    (it.message ?: "unknown")
                return@launch
            }

            layaStatus =
                if (result.ok) {
                    "Laya running · " + result.stdout.take(700)
                } else {
                    "Laya start failed: " +
                        (result.error ?: result.errorCode ?: "unknown")
                }
        }
    }

    fun layaSelfTest() {
        val client = clientOrNull()
        if (client == null) {
            layaStatus = "Bridge token is required first"
            return
        }

        val requestJson = """
            {
              "state": {
                "failure_source": "TOOL",
                "failure_class": "STATE_DRIFT",
                "retryable": "false",
                "effect_class": "READ_ONLY",
                "action_family": "file.search",
                "attempt": "2",
                "outcome_unknown": "false",
                "constitutional_anchor": "TRY_ALTERNATIVE",
                "constitutional_reason": "Same read-only observation already seen. Do not blindly repeat it."
              },
              "questions": {
                "recovery": {
                  "type": "choice",
                  "instructions": "Choose the safest bounded recovery disposition. Prefer progress without repeating an observation that cannot advance the task.",
                  "criteria": {
                    "RETRY_VARIANT": "Retry the same action family with a corrected variant.",
                    "TRY_ALTERNATIVE": "Use a different bounded action family or evidence source.",
                    "ASK_PLANNER": "Escalate to the deliberative planner.",
                    "DEGRADE_PARTIAL": "Return verified partial progress.",
                    "STOP": "Stop safely."
                  }
                }
              }
            }
        """.trimIndent()

        scope.launch {
            layaStatus = "Running Laya self-test…"
            val startedNs = System.nanoTime()

            val result = runCatching {
                client.execute(
                    ToolRequest(
                        tool = "laya.predict",
                        args = mapOf(
                            "request" to requestJson,
                            "timeout" to "60"
                        )
                    )
                )
            }.getOrElse {
                layaStatus =
                    "Laya self-test error: " +
                        (it.message ?: "unknown")
                return@launch
            }

            val elapsedMs =
                ((System.nanoTime() - startedNs) / 1_000_000L)
                    .coerceAtLeast(0L)

            if (!result.ok) {
                layaStatus =
                    "Laya self-test failed: " +
                        (result.error ?: result.errorCode ?: "unknown")
                return@launch
            }

            layaStatus = runCatching {
                val root = JSONObject(result.stdout)
                val answer = root
                    .getJSONObject("answers")
                    .getJSONObject("recovery")
                val choice =
                    answer.optString("choice", "unknown")
                val confidence =
                    answer.optDouble("confidence", Double.NaN)
                val confidenceText =
                    if (confidence.isFinite()) {
                        "%.3f".format(confidence)
                    } else {
                        "NA"
                    }

                "Self-test OK · choice=" + choice +
                    " · confidence=" + confidenceText +
                    " · latency=" + elapsedMs + " ms"
            }.getOrElse {
                "Self-test response received · latency=" +
                    elapsedMs + " ms · " +
                    result.stdout.take(500)
            }
        }
    }

    fun openTermux() {
        val intent =
            context.packageManager.getLaunchIntentForPackage(
                TermuxBridgeAutoStarter.TERMUX_PACKAGE
            )
        if (intent != null) {
            context.startActivity(intent)
        } else {
            bridgeStatus = "Termux is not installed"
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Bridge & Laya",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            "Local control center · loopback only. Daily use should not require manual Termux commands.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = bridgeUrl,
            onValueChange = onBridgeUrl,
            label = { Text("Bridge URL") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = bridgeToken,
            onValueChange = {
                onBridgeToken(
                    LumenaPreferences.normalizeBridgeToken(it)
                )
            },
            label = { Text("Bridge token") },
            singleLine = true,
            visualTransformation =
                if (revealToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { revealToken = !revealToken }
            ) {
                Text(if (revealToken) "Hide token" else "Show token")
            }
            OutlinedButton(
                onClick = {
                    val token =
                        LumenaPreferences
                            .normalizeBridgeToken(bridgeToken)
                    if (token.isBlank()) {
                        bridgeStatus = "Bridge token is empty"
                    } else {
                        copyText("Lumena bridge token", token)
                        bridgeStatus = "Bridge token copied"
                    }
                }
            ) {
                Text("Copy token")
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = ::startBridge) {
                Text("Start Bridge")
            }
            OutlinedButton(onClick = ::bridgeSelfTest) {
                Text("Self-test")
            }
        }

        Text(
            bridgeStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    copyText(
                        "Lumena Bridge install/update",
                        bridgeInstallCommand()
                    )
                    bridgeStatus =
                        "Pinned install/update command copied"
                }
            ) {
                Text("Copy install/update")
            }
            TextButton(onClick = ::openTermux) {
                Text("Open Termux")
            }
        }

        Text(
            "Install/update is pinned to a fixed repository commit and prints the Bridge token after start.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            "Laya System-1",
            style = MaterialTheme.typography.titleSmall
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = ::checkLaya) {
                Text("Laya status")
            }
            Button(onClick = ::startLaya) {
                Text("Start Laya")
            }
        }

        OutlinedButton(onClick = ::layaSelfTest) {
            Text("Laya self-test")
        }

        Text(
            layaStatus,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun bridgeInstallCommand(): String =
    """
        set -euo pipefail
        COMMIT="$BRIDGE_INSTALL_COMMIT"
        BASE="https://raw.githubusercontent.com/Kvazarbit/Lumena-Android/§COMMIT/termux"
        TMP="§HOME/.lumena-update"
        mkdir -p "§TMP"
        curl -fL "§BASE/bridge.py" -o "§TMP/bridge.py"
        curl -fL "§BASE/install_bridge.sh" -o "§TMP/install_bridge.sh"
        curl -fL "§BASE/configure_read_roots.sh" -o "§TMP/configure_read_roots.sh"
        curl -fL "§BASE/install_laya_system1.sh" -o "§TMP/install_laya_system1.sh"
        chmod 700 "§TMP/"*.sh
        bash "§TMP/install_bridge.sh"
        ~/.lumena/configure_read_roots.sh add shared "§HOME/storage/shared" 2>/dev/null || true
        pkill -f "§HOME/.lumena/bridge.py" 2>/dev/null || true
        nohup python "§HOME/.lumena/bridge.py" > "§HOME/.lumena/bridge.log" 2>&1 &
        sleep 2
        echo "===== BRIDGE ROOT ====="
        curl -s http://127.0.0.1:8765/
        echo
        echo "===== BRIDGE TOKEN ====="
        TOKEN="§(cat "§HOME/.lumena/bridge_token")"
        echo "§TOKEN"
        echo "===== BRIDGE HEALTH ====="
        curl -s -X POST http://127.0.0.1:8765/tool \
          -H "Authorization: Bearer §TOKEN" \
          -H "Content-Type: application/json" \
          -d '{"tool":"health","args":{}}'
        echo
    """.trimIndent().replace('§', '$')
