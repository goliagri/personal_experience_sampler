package pes.app

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import pes.store.TokenSource

/**
 * Drive authorization via Google Identity's AuthorizationClient (spec §11):
 * no client secret ships in the app — the OAuth grant is tied to the app's
 * package name + signing key (registered as an Android OAuth client in the
 * same Google Cloud project as the desktop client). Tokens are managed by
 * Play services; nothing is stored by us beyond a "connected" flag.
 */
object DriveConnection {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val PREFS = "pes"
    private const val KEY_CONNECTED = "drive_connected"

    fun connected(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CONNECTED, false)

    fun setConnected(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_CONNECTED, value).apply()
    }

    fun authorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
}

/** Blocking token source for the sync worker: Play services silently returns
 * a token once the user has consented; if consent is needed (revoked or
 * never granted) sync fails softly until Settings > Connect is used. */
class GmsTokenSource(private val context: Context) : TokenSource {
    override fun accessToken(forceRefresh: Boolean): String {
        try {
            val result = Tasks.await(
                Identity.getAuthorizationClient(context)
                    .authorize(DriveConnection.authorizationRequest()),
                30, TimeUnit.SECONDS,
            )
            if (result.hasResolution()) {
                DriveConnection.setConnected(context, false)
                throw IOException("Drive authorization required; reconnect in Settings")
            }
            return result.accessToken
                ?: throw IOException("Drive authorization returned no token")
        } catch (e: ExecutionException) {
            throw IOException("Drive authorization failed", e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Drive authorization interrupted", e)
        } catch (e: java.util.concurrent.TimeoutException) {
            throw IOException("Drive authorization timed out", e)
        }
    }
}

@Composable
fun DriveSection(host: EngineHost, refresh: Int, bump: () -> Unit) {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(DriveConnection.connected(context)) }
    var status by remember { mutableStateOf<String?>(null) }
    val syncState by androidx.compose.runtime.produceState<Triple<String?, String?, String?>?>(null, refresh) {
        value = host.withEngine {
            Triple(
                it.db.kvGet("sync_meta", "last_sync"),
                it.db.kvGet("sync_meta", "last_sync_result"),
                it.db.kvGet("sync_meta", "last_sync_error")?.takeIf { e -> e.isNotEmpty() },
            )
        }
    }

    val consent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val granted = result.data?.let {
            runCatching {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(it)
            }.getOrNull()
        }
        if (granted != null && !granted.hasResolution()) {
            DriveConnection.setConnected(context, true)
            connected = true
            status = "Google Drive connected"
            SyncWorker.syncNow(context)
        } else {
            status = "Drive authorization cancelled"
        }
        bump()
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Google Drive sync", style = MaterialTheme.typography.titleMedium)
        Text(
            if (connected) "Drive connected" else "Not connected",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        syncState?.let { (last, result, error) ->
            Text(
                "Last successful sync: ${last ?: "never"}" +
                    (result?.let { r -> " ($r)" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (error != null) {
                Text(
                    "Last sync failed: $error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (connected) {
            OutlinedButton(onClick = {
                DriveConnection.setConnected(context, false)
                connected = false
                status = "Drive disconnected"
            }) { Text("Disconnect") }
        } else {
            OutlinedButton(onClick = {
                Identity.getAuthorizationClient(context)
                    .authorize(DriveConnection.authorizationRequest())
                    .addOnSuccessListener { result ->
                        val pending = result.pendingIntent
                        if (result.hasResolution() && pending != null) {
                            consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                        } else {
                            DriveConnection.setConnected(context, true)
                            connected = true
                            status = "Google Drive connected"
                            SyncWorker.syncNow(context)
                        }
                    }
                    .addOnFailureListener { status = "Drive connect failed: ${it.message}" }
            }) { Text("Connect Google Drive…") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
