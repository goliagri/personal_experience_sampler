package pes.app

import android.app.Application
import java.io.File
import java.security.SecureRandom
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import pes.Engine
import pes.store.Db

/**
 * Owns the engine on a dedicated thread. The Db (like the desktop's sqlite3
 * connection) is single-threaded, so every engine touch — UI queries, alarm
 * receivers, notification actions, sync — goes through this executor. The
 * ping path stays local-first: nothing here touches the network.
 */
class EngineHost(private val app: Application) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pes-engine") }
    val dispatcher = executor.asCoroutineDispatcher()
    val notifier = AndroidNotifier(app)
    lateinit var engine: Engine
        private set

    init {
        executor.submit {
            val db = Db(File(app.filesDir, "pes.sqlite").path)
            engine = Engine(db, deviceId(db), notifier)
            notifier.engine = engine
            engine.ensureConfig(TimeZone.getDefault().id)
            engine.start()
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
        executor.execute { block(engine) }
    }

    fun <T> call(block: (Engine) -> T): T = executor.submit(Callable { block(engine) }).get()

    suspend fun <T> withEngine(block: (Engine) -> T): T = withContext(dispatcher) { block(engine) }

    /** Run one engine tick and move the single exact alarm to the next wake. */
    fun tickAndReschedule(done: () -> Unit = {}) {
        post { engine ->
            val next = engine.tick()
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
        ensureNotificationChannel(this)
        host = EngineHost(this)
        host.tickAndReschedule()
        SyncWorker.ensurePeriodic(this)
    }
}

fun android.content.Context.pesHost(): EngineHost = (applicationContext as PesApp).host
