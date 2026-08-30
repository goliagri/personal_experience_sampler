package pes.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import pes.Engine
import pes.Syncer
import pes.store.AuthorizedSession
import pes.store.Db
import pes.store.DriveStore

/**
 * Sync (spec §11): WorkManager periodic (1 h, network-connected) plus
 * one-shot runs on triggers (after answers, from Settings). Like the desktop
 * sync worker, this opens its own Db (connections are single-threaded; WAL
 * allows it alongside the engine thread) so the ping path never waits on the
 * network.
 */
class SyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!DriveConnection.connected(context)) return Result.success()
        val db = Db(File(context.filesDir, "pes.sqlite").path)
        try {
            val deviceId = db.kvGet("device", "device_id") ?: return Result.success()
            val notifier = AndroidNotifier(context)
            val engine = Engine(db, deviceId, notifier)
            notifier.engine = engine
            val store = DriveStore(AuthorizedSession(GmsTokenSource(context)), db)
            val syncer = Syncer(engine, store)
            var prefix = ""
            val result = if (inputData.getBoolean(KEY_RESTORE, false)) {
                // §8.6: rebuild the cloud folder from the local cache first.
                val restored = syncer.restore()
                val n = restored.uploaded.size + restored.restored.size + restored.docs.size
                prefix = "restored $n file(s); "
                restored.sync
            } else {
                syncer.sync()
            }
            db.kvSet("sync_meta", "last_sync_error", "")
            db.kvSet(
                "sync_meta", "last_sync_result",
                prefix + "imported ${result.imported.size} file(s), exported ${result.exported.size}," +
                    " backfilled ${result.backfilled}" +
                    (if (result.snapshot != null) ", snapshot taken" else "") +
                    (if (result.warnings.isEmpty()) "" else "; ${result.warnings.joinToString("; ")}"),
            )
            // Imported events may change pending samples; move the alarm.
            Alarms.schedule(context, engine.nextWake(engine.clock.now()))
            return Result.success()
        } catch (e: Exception) {
            // Surface the failure in Settings instead of dying silently.
            runCatching { db.kvSet("sync_meta", "last_sync_error", e.toString().take(500)) }
            return if (e is IOException) Result.retry() else Result.failure()
        } finally {
            db.close()
        }
    }

    companion object {
        private const val KEY_RESTORE = "restore"

        fun ensurePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "pes-sync", ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /** Unique name of manual runs so Settings can observe their state. */
        const val MANUAL_WORK = "pes-sync-manual"

        fun syncNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>().build(),
            )
        }

        /** Restore procedure (§8.6) followed by a normal sync. */
        fun restoreNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                MANUAL_WORK, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setInputData(workDataOf(KEY_RESTORE to true))
                    .build(),
            )
        }
    }
}
