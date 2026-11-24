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

enum class WatchCategory {
    INSTALL_REVIEW,
    ADB_PRIVACY,
    INTERNAL_STATE,
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
    val category: WatchCategory = WatchCategory.INSTALL_REVIEW,
) {
    fun isOk(current: String?) = (current?.trim() ?: "") in okValues ||
        (current == null && "null" in okValues)

    /** 当前值的语义化描述：防护生效 → onText；系统默认 → offText；其他 → 显示原值。 */
    fun statusText(current: String?): String = when {
        isOk(current) -> onText
        current?.trim() == restore -> offText
        else -> "当前值 ${current ?: "（未设置）"}，建议恢复防护"
    }
}

data class ForbiddenEntry(val key: String, val value: String)

object SecureSettings {

    /** 开关类设置。开启 = 已按推荐配置（放行/关闭审查），关闭 = 系统默认。 */
    val items = listOf(
        WatchItem(
            Ns.GLOBAL, "package_verifier_enable", "跳过安装包验证",
            "关闭安装前系统级 APK 安全验证",
            "0", "1",
            onText = "已关闭系统验证",
            offText = "未防护 · 安装前会执行系统验证",
            category = WatchCategory.INSTALL_REVIEW,
        ),
        WatchItem(
            Ns.GLOBAL, "package_verifier_user_consent", "免除验证结果确认",
            "不再弹出包验证完成后的二次确认框",
            "-1", "1",
            onText = "已设为不再询问",
            offText = "未防护 · 验证后会弹出确认框",
            category = WatchCategory.INSTALL_REVIEW,
        ),
        WatchItem(
            Ns.SECURE, "installer_full_safe_version", "跳过完整安全审查",
            "关闭小米安装器完整安全审查流程",
            "0", "1",
            onText = "已关闭完整审查",
            offText = "未防护 · 启用完整审查（易被云端回滚）",
            category = WatchCategory.INSTALL_REVIEW,
        ),
        WatchItem(
            Ns.SECURE, "com.miui.packageinstaller_setting_key_show_security_warning",
            "屏蔽安装风险警告", "隐藏安装界面的“存在风险”类安全提示",
            "0", "1",
            onText = "已屏蔽风险警告",
            offText = "未防护 · 安装时会显示安全警告",
            category = WatchCategory.INSTALL_REVIEW,
        ),
        WatchItem(
            Ns.SYSTEM, "virus_scan_install", "关闭安装病毒扫描",
            "跳过安全中心在安装时的联网病毒查杀",
            "0", "1",
            onText = "已关闭安装查杀",
            offText = "未防护 · 安装时会联网扫毒",
            category = WatchCategory.INSTALL_REVIEW,
        ),
        WatchItem(
            Ns.SECURE, "install_non_market_apps", "允许未知来源安装",
            "允许自由安装应用商店以外来源的 APK",
            "1", "0",
            onText = "已允许未知来源",
            offText = "未防护 · 仅允许应用商店安装",
            category = WatchCategory.INSTALL_REVIEW,
        ),
        WatchItem(
            Ns.GLOBAL, "verifier_verify_adb_installs", "ADB 安装免验证",
            "通过 ADB 安装应用时跳过系统包验证",
            "0", "1",
            onText = "ADB 安装已跳过验证",
            offText = "未防护 · ADB 安装仍会执行验证",
            category = WatchCategory.ADB_PRIVACY,
        ),
        WatchItem(
            Ns.GLOBAL, "adb_install_need_confirm", "ADB 免确认静默安装",
            "免除 ADB 安装时手机屏幕弹窗确认",
            "0", "1",
            onText = "ADB 已启用静默安装",
            offText = "未防护 · ADB 安装需屏幕手动确认",
            category = WatchCategory.ADB_PRIVACY,
        ),
        WatchItem(
            Ns.GLOBAL, "upload_apk_enable", "禁止上传 APK 信息",
            "阻止安装时将应用信息上传至小米云端",
            "0", "1",
            onText = "已阻止隐私上传",
            offText = "未防护 · 安装信息会上传至云端",
            category = WatchCategory.ADB_PRIVACY,
        ),
        WatchItem(
            Ns.SECURE, "package_verifier_state", "锁定验证器内部标志",
            "保持系统包验证器状态位为正常值 0",
            "0", "1",
            onText = "正常（0）",
            offText = "状态位非 0，建议恢复为 0",
            category = WatchCategory.INTERNAL_STATE,
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
