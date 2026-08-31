package pes.app

import android.app.Application
import java.io.File
import java.security.SecureRandom
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import pes.Clock
import pes.Engine
import pes.SystemClock
import pes.core.str
import pes.store.Db

/**
 * Owns the engine on a dedicated thread. The Db (like the desktop's sqlite3
 * connection) is single-threaded, so every engine touch — UI queries, alarm
 * receivers, notification actions, sync — goes through this executor. The
 * ping path stays local-first: nothing here touches the network.
 */
class EngineHost(
    private val app: Application,
    dbPath: String = File(app.filesDir, "pes.sqlite").path,
    clock: Clock = SystemClock(),
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pes-engine") }
    val dispatcher = executor.asCoroutineDispatcher()
    val notifier = AndroidNotifier(app)
    lateinit var engine: Engine
        private set

    init {
        executor.submit {
            val db = Db(dbPath)
            engine = Engine(db, deviceId(db), notifier, clock)
            notifier.engine = engine
            // A startup failure must not make the app unlaunchable: record it
            // (Settings shows it) and carry on with whatever state exists.
            runCatching {
                engine.ensureConfig(TimeZone.getDefault().id)
                engine.start()
            }.onFailure { CrashLog.record(app, "engine startup", it) }
        }.get()
    }

    /** Device identity (spec §4): stable `phone-<8 hex>` id per install. */
    private fun deviceId(db: Db): String {
        db.kvGet("device", "device_id")?.let { return it }
        val id = "phone-%08x".format(SecureRandom().nextInt())
        db.kvSet("device", "device_id", id)
        return id
    }

    fun post(block: (Engine) -> Unit) {
        executor.execute {
            // An exception here would kill the engine thread and the process;
            // record it instead so Settings can show it.
            runCatching { block(engine) }.onFailure { CrashLog.record(app, "engine task", it) }
        }
    }

    fun <T> call(block: (Engine) -> T): T = executor.submit(Callable { block(engine) }).get()

    suspend fun <T> withEngine(block: (Engine) -> T): T = withContext(dispatcher) { block(engine) }

    /**
     * Like [withEngine] but never lets a failure escape into the UI coroutine.
     * An exception thrown from a screen's engine call used to take the whole
     * process down — losing the answer the user had just typed — and left the
     * app crashing on every relaunch (Tier 3 charter C5 F2). Callers show the
     * failure and stay put.
     */
    suspend fun <T> tryWithEngine(block: (Engine) -> T): Result<T> =
        withContext(dispatcher) { runCatching { block(engine) } }
            .onFailure { CrashLog.record(app, "engine task", it) }

    /** Run one engine tick and move the single exact alarm to the next wake. */
    fun tickAndReschedule(done: () -> Unit = {}) {
        post { engine ->
            val next = engine.tick()
            notifier.reconcile(engine.activeSamples(engine.clock.now()).map { it.str("sample") }.toSet())
            Alarms.schedule(app, next)
            done()
        }
    }
}

class PesApp : Application() {
    lateinit var host: EngineHost
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        ensureNotificationChannel(this)
        host = EngineHost(this)
        host.tickAndReschedule()
        SyncWorker.ensurePeriodic(this)
    }
}

fun android.content.Context.pesHost(): EngineHost = (applicationContext as PesApp).host
