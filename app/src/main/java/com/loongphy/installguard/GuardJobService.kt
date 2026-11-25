package com.loongphy.installguard

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * JobScheduler 执行入口。
 *
 * 内容触发任务一次性消费后必须重新布防。官方要求的模式是：先完成本轮处理，
 * 再用相同 job id schedule 新任务来替代 jobFinished()；系统在任务运行期间
 * 仍会继续监听内容变化，并把变化转交给新任务，因此不会漏掉回滚。
 */
class GuardJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        when (params.jobId) {
            GuardScheduler.JOB_CONTENT -> {
                replacedContentJobs.remove(params.jobId)
                contentRunning = true
            }
            GuardScheduler.JOB_SWEEP -> sweepRunning = true
        }
        val task = scope.launch {
            try {
                if (GuardScheduler.isGuardEnabled(this@GuardJobService)) {
                    val fixes = Guardian.checkAll(this@GuardJobService)
                    if (fixes > 0) Log.i(TAG, "fixed $fixes rolled-back setting(s)")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.e(TAG, "guard job failed", t)
            }

            when (params.jobId) {
                GuardScheduler.JOB_CONTENT -> {
                    if (isActive && GuardScheduler.isGuardEnabled(this@GuardJobService)) {
                        replacedContentJobs.add(params.jobId)
                        if (!GuardScheduler.scheduleContentTrigger(this@GuardJobService)) {
                            replacedContentJobs.remove(params.jobId)
                            jobFinished(params, false)
                        }
                    } else {
                        jobFinished(params, false)
                    }
                }
                GuardScheduler.JOB_SWEEP -> {
                    // 内容任务若在上次触发后异常丢失，由周期巡检重新布防。
                    GuardScheduler.scheduleContentTrigger(this@GuardJobService)
                    jobFinished(params, false)
                }
                else -> jobFinished(params, false)
            }
            clearRunningState(params.jobId)
            runningJobs.remove(params.jobId)
        }
        runningJobs[params.jobId] = task
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val intentionallyReplaced = replacedContentJobs.remove(params.jobId)
        runningJobs.remove(params.jobId)?.cancel()
        clearRunningState(params.jobId)
        val enabled = GuardScheduler.isGuardEnabled(this)

        if (params.jobId == GuardScheduler.JOB_CONTENT) {
            if (!intentionallyReplaced && enabled) {
                GuardScheduler.scheduleContentTrigger(this)
            }
            return false
        }
        return params.jobId == GuardScheduler.JOB_SWEEP && enabled
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun clearRunningState(jobId: Int) {
        when (jobId) {
            GuardScheduler.JOB_CONTENT -> contentRunning = false
            GuardScheduler.JOB_SWEEP -> sweepRunning = false
        }
    }

    companion object {
        private const val TAG = "GuardJobService"
        private val replacedContentJobs: MutableSet<Int> = ConcurrentHashMap.newKeySet()

        /** 内容任务触发后不再出现在 getPendingJob() 中；用进程内状态避免 ensure 误杀运行中任务。 */
        @Volatile
        internal var contentRunning = false

        @Volatile
        internal var sweepRunning = false
    }
}
