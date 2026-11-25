package com.loongphy.installguard

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * 系统级守护调度。
 *
 * JOB_CONTENT 使用 TriggerContentUri：即使应用进程已经死亡，SettingsProvider
 * 通知变化时系统也会重新拉起 JobService。它不能 persisted，因此每次触发后
 * 需要在 JobService 里用相同 job id 重新布防。
 *
 * JOB_SWEEP 是 15 分钟持久化巡检，覆盖内容触发器漏报、被取消或开机后
 * 尚未重建的场景。
 */
object GuardScheduler {
    const val JOB_CONTENT = 7101
    const val JOB_SWEEP = 7102

    private const val TAG = "GuardScheduler"
    private const val CONTENT_UPDATE_DELAY_MS = 250L
    private const val CONTENT_MAX_DELAY_MS = 1_000L
    private const val SWEEP_INTERVAL_MS = 15 * 60 * 1_000L

    fun isGuardEnabled(context: Context): Boolean =
        context.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(GuardService.PREF_ENABLED, false)

    /** 确保两类系统任务存在；已存在时不重复替换，避免重置周期任务窗口。 */
    fun ensureScheduled(context: Context) {
        if (!isGuardEnabled(context)) return
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_CONTENT) == null && !GuardJobService.contentRunning) {
            scheduleContentTrigger(context)
        }
        if (scheduler.getPendingJob(JOB_SWEEP) == null && !GuardJobService.sweepRunning) {
            scheduleSweep(context)
        }
    }

    fun cancelAll(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        scheduler.cancel(JOB_CONTENT)
        scheduler.cancel(JOB_SWEEP)
    }

    /**
     * 布置内容监听任务。触发后该任务即消费完毕，所以 JobService 完成扫描后
     * 必须再次调用本方法重新布防。
     */
    fun scheduleContentTrigger(context: Context): Boolean {
        if (!isGuardEnabled(context)) return false
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val builder = JobInfo.Builder(
            JOB_CONTENT,
            ComponentName(context, GuardJobService::class.java),
        )
        Guardian.watched.forEach { item ->
            builder.addTriggerContentUri(
                JobInfo.TriggerContentUri(Guardian.uriFor(item), 0),
            )
        }
        val job = builder
            .setTriggerContentUpdateDelay(CONTENT_UPDATE_DELAY_MS)
            .setTriggerContentMaxDelay(CONTENT_MAX_DELAY_MS)
            .build()
        val ok = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        if (!ok) Log.w(TAG, "schedule content trigger failed")
        return ok
    }

    /** 15 分钟兜底巡检；persisted 使其重启后仍由系统恢复。 */
    fun scheduleSweep(context: Context): Boolean {
        if (!isGuardEnabled(context)) return false
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
        val job = JobInfo.Builder(
            JOB_SWEEP,
            ComponentName(context, GuardJobService::class.java),
        )
            .setPeriodic(maxOf(JobInfo.getMinPeriodMillis(), SWEEP_INTERVAL_MS))
            .setPersisted(true)
            .build()
        val ok = scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
        if (!ok) Log.w(TAG, "schedule sweep failed")
        return ok
    }
}
