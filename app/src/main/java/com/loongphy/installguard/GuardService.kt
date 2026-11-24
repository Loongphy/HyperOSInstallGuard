package com.loongphy.installguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务：监视所有受监控的设置项。
 * 使用 ContentObserver（事件驱动，不轮询）——耗电几乎为零：
 * 只有 SettingsProvider 通知变更时进程才被唤醒。
 * 每 5 分钟做一次兜底扫描，覆盖可能丢失的通知。
 *
 * 目标值取用户在界面最后一次设定的快照（snap_<key>），
 * 没有快照的键沿用默认防护值。云端/MIUI 把某个键改回别的值时，
 * observer 触发，服务立即写回目标值。
 */
class GuardService : Service() {

    companion object {
        const val ACTION_START = "com.loongphy.installguard.GUARD_START"
        const val ACTION_STOP = "com.loongphy.installguard.GUARD_STOP"
        const val PREFS = "guard_prefs"
        const val PREF_ENABLED = "guard_enabled"
        const val PREF_FIXES = "guard_fixes"
        const val SNAP_PREFIX = "snap_"
        private const val NOTIF_ID = 7001
        private const val CHANNEL = "guard"
        private const val TAG = "GuardService"
        private const val SWEEP_MS = 5 * 60 * 1000L

        var running = false
            private set
        var lastFixAt = 0L
            private set
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val resolver: ContentResolver get() = contentResolver
    private val watched = SecureSettings.items + SecureSettings.listItems
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
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "安装守护", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(NOTIF_ID, buildNotif())
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

    private fun uriFor(item: WatchItem): Uri = when (item.ns) {
        Ns.GLOBAL -> Settings.Global.getUriFor(item.key)
        Ns.SECURE -> Settings.Secure.getUriFor(item.key)
        Ns.SYSTEM -> Settings.System.getUriFor(item.key)
    }

    private fun readFor(item: WatchItem): String? = runCatching {
        when (item.ns) {
            Ns.GLOBAL -> Settings.Global.getString(resolver, item.key)
            Ns.SECURE -> Settings.Secure.getString(resolver, item.key)
            Ns.SYSTEM -> Settings.System.getString(resolver, item.key)
        }
    }.getOrNull()

    private fun registerObservers() {
        watched.forEach { item ->
            val obs = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) {
                    checkItem(item)
                }
            }
            runCatching {
                resolver.registerContentObserver(uriFor(item), false, obs)
                observers.add(obs)
            }
        }
    }

    /**
     * 检查一个键：当前值与目标不符就写回。
     * 目标 = 用户手动设定的快照；无快照时用默认防护值（isOk 判定）。
     * 写路径走 SecureSettings.write —— 直写失败时 Shizuku 兜底，
     * 覆盖 system 命名空间这类直写无权限的键。
     */
    private fun checkItem(item: WatchItem) {
        val current = readFor(item)
        val p = getSharedPreferences(PREFS, MODE_PRIVATE)
        val snap = p.getString(SNAP_PREFIX + item.key, null)
        val target = snap ?: item.expected
        val okNow = if (snap != null) {
            current?.trim() == snap.trim()
        } else {
            item.isOk(current)
        }
        if (!okNow) {
            scope.launch {
                if (SecureSettings.write(resolver, item, target)) {
                    fixesThisSession++
                    lastFixAt = System.currentTimeMillis()
                    p.edit().putInt(PREF_FIXES, p.getInt(PREF_FIXES, 0) + 1).apply()
                    updateNotif()
                } else {
                    Log.w(TAG, "fix failed: ${item.ns}/${item.key} -> $target")
                }
            }
        }
    }

    /** 全面扫描：覆盖事件可能丢失的通知。 */
    private fun checkAll() {
        watched.forEach { checkItem(it) }
    }
}

/** Rearranque del guard tras boot si estaba activado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = context.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(GuardService.PREF_ENABLED, false)
        if (enabled) {
            runCatching {
                context.startForegroundService(
                    Intent(context, GuardService::class.java)
                        .setAction(GuardService.ACTION_START),
                )
            }
        }
    }
}
