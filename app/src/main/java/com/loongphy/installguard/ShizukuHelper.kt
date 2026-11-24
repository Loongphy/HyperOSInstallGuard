package com.loongphy.installguard

import android.content.pm.PackageManager
import java.lang.reflect.Method
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

enum class ShizukuStatus { UNAVAILABLE, NEED_AUTH, READY }

data class ExecResult(val code: Int, val out: String, val err: String) {
    val ok get() = code == 0
}

object ShizukuHelper {
    private const val REQ_PERMISSION = 6117

    private val _status = MutableStateFlow(ShizukuStatus.UNAVAILABLE)
    val status: StateFlow<ShizukuStatus> = _status.asStateFlow()

    private var attached = false

    fun attach() {
        if (attached) {
            refresh()
            return
        }
        attached = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky { refresh() }
            Shizuku.addBinderDeadListener { _status.value = ShizukuStatus.UNAVAILABLE }
            Shizuku.addRequestPermissionResultListener { _, _ -> refresh() }
        }
        refresh()
    }

    fun refresh() {
        _status.value = when {
            !runCatching { Shizuku.pingBinder() }.getOrDefault(false) ->
                ShizukuStatus.UNAVAILABLE
            runCatching { Shizuku.checkSelfPermission() }
                .getOrDefault(PackageManager.PERMISSION_DENIED) != PackageManager.PERMISSION_GRANTED ->
                ShizukuStatus.NEED_AUTH
            else -> ShizukuStatus.READY
        }
    }

    fun requestPermission() {
        runCatching {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.requestPermission(REQ_PERMISSION)
            }
        }
    }

    // Shizuku.newProcess 在 API 13 中为 private，通过反射调用（社区通用做法）
    private val newProcessMethod: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply { isAccessible = true }
    }

    /** 以 shell(uid 2000) 身份执行命令，等效 adb shell。 */
    suspend fun exec(vararg args: String): ExecResult = withContext(Dispatchers.IO) {
        runCatching {
            val process = newProcessMethod.invoke(null, arrayOf(*args), null, null) as Process
            val out = process.inputStream.bufferedReader().readText()
            val err = process.errorStream.bufferedReader().readText()
            ExecResult(process.waitFor(), out.trim(), err.trim())
        }.getOrElse { ExecResult(-1, "", it.message ?: it.javaClass.simpleName) }
    }
}
