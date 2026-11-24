package com.loongphy.installguard

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class Ns(val cli: String) {
    GLOBAL("global"), SECURE("secure"), SYSTEM("system")
}

/**
 * 一个被监控的系统设置项。
 * [expected] 是防护值（写入它即"关闭校验/放行"）；[restore] 是系统默认（有风险）值。
 * [onText]/[offText] 是当前值对应的语义化状态描述，替代裸数字展示给用户。
 */
data class WatchItem(
    val ns: Ns,
    val key: String,
    val title: String,
    val summary: String,
    val expected: String,
    val restore: String,
    val okValues: Set<String> = setOf(expected),
    val onText: String = "",
    val offText: String = "",
) {
    fun isOk(current: String?) = (current?.trim() ?: "") in okValues ||
        (current == null && "null" in okValues)

    /** 当前值的语义化描述：防护生效 → onText；系统默认 → offText；其他 → 显示原值。 */
    fun statusText(current: String?): String = when {
        isOk(current) -> onText
        current?.trim() == restore -> offText
        else -> "当前值 ${current ?: "（未设置）"}，非预期"
    }
}

data class ForbiddenEntry(val key: String, val value: String)

object SecureSettings {

    /** 开关类设置。开启 = 已按推荐配置（放行/关闭审查），关闭 = 系统默认。 */
    val items = listOf(
        WatchItem(
            Ns.GLOBAL, "package_verifier_enable", "安装包验证器",
            "安装前对 APK 做系统级安全验证",
            "0", "1",
            onText = "已关闭，安装时不验证",
            offText = "开启中，安装前会验证",
        ),
        WatchItem(
            Ns.GLOBAL, "package_verifier_user_consent", "验证结果需确认",
            "验证完成后是否再弹一次确认框（-1 = 不再询问）",
            "-1", "1",
            onText = "不询问，验证后直接装",
            offText = "开启中，会弹确认框",
        ),
        WatchItem(
            Ns.GLOBAL, "verifier_verify_adb_installs", "ADB 安装也验证",
            "通过 adb install 安装时是否仍执行包验证",
            "0", "1",
            onText = "adb 安装跳过验证",
            offText = "adb 安装也会验证",
        ),
        WatchItem(
            Ns.GLOBAL, "upload_apk_enable", "APK 信息上传",
            "安装时把 APK 信息上传到小米服务器（纯隐私项，云端会回滚）",
            "0", "1",
            onText = "已关闭，不上传",
            offText = "开启中，安装信息会上传小米",
        ),
        WatchItem(
            Ns.GLOBAL, "adb_install_need_confirm", "ADB 安装弹确认框",
            "adb install 时是否在手机屏幕上弹确认框",
            "0", "1",
            onText = "不弹框，静默安装",
            offText = "开启中，会弹确认框",
        ),
        WatchItem(
            Ns.SECURE, "install_non_market_apps", "允许未知来源安装",
            "是否允许安装应用商店以外来源的 APK",
            "1", "0",
            onText = "允许，可自由安装 APK",
            offText = "禁止，只能走应用商店",
        ),
        WatchItem(
            Ns.SECURE, "installer_full_safe_version", "安装器完整安全校验",
            "小米安装器的完整安全审查流程（云端会回滚）",
            "0", "1",
            onText = "已关闭完整审查",
            offText = "开启中，走完整审查",
        ),
        WatchItem(
            Ns.SECURE, "com.miui.packageinstaller_setting_key_show_security_warning",
            "安装安全警告", "安装时是否显示“存在风险”类安全警告",
            "0", "1",
            onText = "不显示警告",
            offText = "开启中，会显示安全警告",
        ),
        WatchItem(
            Ns.SECURE, "package_verifier_state", "验证器内部标志",
            "系统自维护的标志位，通常为 0，无需理解",
            "0", "1",
            onText = "0（正常）",
            offText = "非 0，建议修复回 0",
        ),
        WatchItem(
            Ns.SYSTEM, "virus_scan_install", "安装病毒扫描",
            "安全中心在安装时做病毒云查杀",
            "0", "1",
            onText = "已关闭，安装不扫描",
            offText = "开启中，安装会扫毒",
        ),
    )

    /** 黑名单设置键。 */
    val forbiddenKey = WatchItem(
        Ns.SECURE, "packageinstaller_forbidden_app_list", "安全审核黑名单",
        "未通过小米安全审核、会被禁止安装的应用", "[]", "",
        okValues = setOf("[]", "", "null"),
    )
    val forbiddenOfflineKey = WatchItem(
        Ns.SECURE, "packageinstaller_network_error_forbidden_app_list",
        "黑名单（离线缓存）", "网络异常时使用的审核黑名单", "[]", "",
        okValues = setOf("[]", "", "null"),
    )
    val listItems = listOf(forbiddenKey, forbiddenOfflineKey)

    fun read(resolver: ContentResolver, item: WatchItem): String? = runCatching {
        when (item.ns) {
            Ns.GLOBAL -> Settings.Global.getString(resolver, item.key)
            Ns.SECURE -> Settings.Secure.getString(resolver, item.key)
            Ns.SYSTEM -> Settings.System.getString(resolver, item.key)
        }
    }.getOrNull()

    /** 先尝试直写（需要 WRITE_SECURE_SETTINGS），失败则走 Shizuku shell。 */
    suspend fun write(resolver: ContentResolver, item: WatchItem, value: String): Boolean {
        val direct = runCatching {
            when (item.ns) {
                Ns.GLOBAL -> Settings.Global.putString(resolver, item.key, value)
                Ns.SECURE -> Settings.Secure.putString(resolver, item.key, value)
                Ns.SYSTEM -> Settings.System.putString(resolver, item.key, value)
            }
        }.getOrDefault(false)
        if (direct) return true
        return ShizukuHelper.exec("settings", "put", item.ns.cli, item.key, value).ok
    }

    /** MIUI 自定义 AppOps：读取应用列表（拒绝后 getInstalledPackages 只返回可见子集）。 */
    const val MIUI_OP_GET_INSTALLED_APPS = 10022

    /**
     * 通过反射查 MIUIOP(10022) 的当前模式。
     * 0=允许 1=拒绝 2=错误 3=默认 5=询问。无权限弹窗副作用。
     */
    fun appListOpMode(context: Context): Int = runCatching {
        val mgr = context.getSystemService(Context.APP_OPS_SERVICE)
        val m = mgr.javaClass.getMethod(
            "checkOpNoThrow", Int::class.java, Int::class.java, String::class.java,
        )
        m.invoke(
            mgr, MIUI_OP_GET_INSTALLED_APPS,
            android.os.Process.myUid(), context.packageName,
        ) as Int
    }.getOrDefault(-1)

    private val entryRegex = Regex("\\{\"key\":\"([0-9a-fA-F]+)\",\"value\":\"([0-9a-fA-F]+)\"\\}")

    /** 解析黑名单 JSON：[{"key":"...","value":"..."}, ...] */
    fun parseForbidden(raw: String?): List<ForbiddenEntry> =
        entryRegex.findAll(raw.orEmpty())
            .map { ForbiddenEntry(it.groupValues[1].lowercase(), it.groupValues[2].lowercase()) }
            .toList()

    fun serializeForbidden(entries: List<ForbiddenEntry>): String =
        entries.joinToString(",", "[", "]") { "{\"key\":\"${it.key}\",\"value\":\"${it.value}\"}" }

    /**
     * 哈希 -> 命中说明。对全部已安装包计算三类 SHA-256：
     * 包名、签名证书、APK 文件，尽力还原黑名单条目对应的应用。
     */
    suspend fun buildHashIndex(context: Context): Map<String, String> = withContext(Dispatchers.IO) {
        val index = HashMap<String, String>()
        val pm = context.packageManager
        val md = MessageDigest.getInstance("SHA-256")
        runCatching {
            pm.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES).forEach { info ->
                val pkg = info.packageName
                index.putIfAbsent(sha256(md, pkg.toByteArray()), "包名 $pkg")
                info.signingInfo?.apkContentsSigners?.forEach { sig ->
                    index.putIfAbsent(sha256(md, sig.toByteArray()), "$pkg 的签名证书")
                }
                runCatching {
                    val path = info.applicationInfo?.sourceDir ?: return@runCatching
                    val f = java.io.File(path)
                    if (f.canRead()) index.putIfAbsent(sha256File(md, f), "APK 文件 $pkg")
                }
            }
        }
        index
    }

    private fun sha256(md: MessageDigest, bytes: ByteArray): String {
        md.reset()
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(md: MessageDigest, f: java.io.File): String {
        md.reset()
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
