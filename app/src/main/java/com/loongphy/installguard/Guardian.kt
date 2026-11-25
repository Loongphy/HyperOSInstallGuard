package com.loongphy.installguard

import android.content.Context
import android.net.Uri
import android.provider.Settings
import android.util.Log

/** 守护规则执行器：前台服务与 JobScheduler 共用同一套判定/修复逻辑。 */
object Guardian {
    private const val TAG = "Guardian"

    val watched: List<WatchItem> = SecureSettings.items + SecureSettings.listItems

    fun uriFor(item: WatchItem): Uri = when (item.ns) {
        Ns.GLOBAL -> Settings.Global.getUriFor(item.key)
        Ns.SECURE -> Settings.Secure.getUriFor(item.key)
        Ns.SYSTEM -> Settings.System.getUriFor(item.key)
    }

    /**
     * 检查一个键：当前值与目标不符就写回。
     * 目标 = 用户手动设定的快照；无快照时用默认防护值（isOk 判定）。
     * @return true 表示本次实际修复了一个回滚项。
     */
    suspend fun checkItem(context: Context, item: WatchItem): Boolean {
        val resolver = context.contentResolver
        val current = SecureSettings.read(resolver, item)
        val prefs = context.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
        val snap = prefs.getString(GuardService.SNAP_PREFIX + item.key, null)
        val target = snap ?: item.expected
        val okNow = if (snap != null) {
            current?.trim() == snap.trim()
        } else {
            item.isOk(current)
        }
        if (okNow) return false

        return if (SecureSettings.write(resolver, item, target)) {
            prefs.edit()
                .putInt(GuardService.PREF_FIXES, prefs.getInt(GuardService.PREF_FIXES, 0) + 1)
                .putLong(GuardService.PREF_LAST_FIX_AT, System.currentTimeMillis())
                .apply()
            true
        } else {
            Log.w(TAG, "fix failed: ${item.ns}/${item.key} -> $target")
            false
        }
    }

    /** 全面扫描：覆盖事件可能丢失的通知。返回实际修复的键数量。 */
    suspend fun checkAll(context: Context): Int {
        var fixes = 0
        watched.forEach { item ->
            if (checkItem(context, item)) fixes++
        }
        return fixes
    }
}
