package com.loongphy.installguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：进程存活时对受监控设置项做事件驱动实时修复。
 *
 * 它只是第一层防线：HyperOS 可能停止或冻结进程，因此持续守护由
 * GuardScheduler 的 TriggerContentUri JobService 承担；即使本进程死亡，
 * 系统也会在设置变化后拉起 GuardJobService 修复。
 */
class GuardService : Service() {

    companion object {
        const val ACTION_START = "com.loongphy.installguard.GUARD_START"
        const val ACTION_STOP = "com.loongphy.installguard.GUARD_STOP"
        const val PREFS = "guard_prefs"
        const val PREF_ENABLED = "guard_enabled"
        const val PREF_FIXES = "guard_fixes"
        const val PREF_LAST_FIX_AT = "guard_last_fix_at"
        const val SNAP_PREFIX = "snap_"
        private const val NOTIF_ID = 7001
        private const val CHANNEL = "guard"
        private const val SWEEP_MS = 5 * 60 * 1000L

        var running = false
            private set
        var lastFixAt = 0L
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resolver: ContentResolver get() = contentResolver
    private val watched = Guardian.watched
    private val observers = mutableListOf<ContentObserver>()
    private var fixesThisSession = 0

    private val sweep = object : Runnable {
        override fun run() {
            checkAll()
            handler.postDelayed(this, SWEEP_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (GuardScheduler.isGuardEnabled(this)) {
            GuardScheduler.ensureScheduled(this)
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "安装守护", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(
            NOTIF_ID,
            buildNotif(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        registerObservers()
        handler.postDelayed(sweep, SWEEP_MS)
        running = true
        // 启动时立即全面检查：服务停摆期间被云端回滚的键当场修复。
        checkAll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        observers.forEach { resolver.unregisterContentObserver(it) }
        observers.clear()
        handler.removeCallbacks(sweep)
        scope.cancel()
        running = false
        super.onDestroy()
    }

    private fun buildNotif(): Notification {
        val total = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(PREF_FIXES, 0)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_guard)
            .setContentTitle("安装守护运行中")
            .setContentText(
                if (total == 0) "正在监视云端回滚"
                else "已自动修复回滚：$total 次",
            )
            .setOngoing(true)
            .build()
    }

    private fun updateNotif() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif())
    }

    private fun registerObservers() {
        watched.forEach { item ->
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    checkItem(item)
                }
            }
            runCatching {
                resolver.registerContentObserver(Guardian.uriFor(item), false, obs)
                observers.add(obs)
            }
        }
    }

    private fun checkItem(item: WatchItem) {
        scope.launch {
            if (Guardian.checkItem(this@GuardService, item)) {
                fixesThisSession++
                lastFixAt = System.currentTimeMillis()
                updateNotif()
            }
        }
    }

    /** 全面扫描：覆盖事件可能丢失的通知。 */
    private fun checkAll() {
        watched.forEach { checkItem(it) }
    }
}

/** 开机或应用更新后重新布防；JobScheduler 路径不依赖前台服务能否启动。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        val enabled = context.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(GuardService.PREF_ENABLED, false)
        if (!enabled) return

        GuardScheduler.ensureScheduled(context)
        runCatching {
            context.startForegroundService(
                Intent(context, GuardService::class.java)
                    .setAction(GuardService.ACTION_START),
            )
        }
    }
}
