package com.loongphy.installguard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical

enum class Page { Main, Blacklist }

class MonitorState(private val activity: ComponentActivity) {
    val values = mutableStateMapOf<String, String?>()
    var secureWritable by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var hashIndex by mutableStateOf<Map<String, String>?>(null)
    var indexing by mutableStateOf(false)
    var appListDenied by mutableStateOf(false)
    var guardActive by mutableStateOf(false)
    var guardFixes by mutableStateOf(0)
    private val resolver get() = activity.contentResolver

    fun refreshGuard() {
        val p = activity.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
        val enabled = p.getBoolean(GuardService.PREF_ENABLED, false)
        if (enabled && !GuardService.running) {
            // 进程被杀过：用户已开启守护，重启服务恢复生效。
            runCatching {
                activity.startForegroundService(
                    android.content.Intent(activity, GuardService::class.java)
                        .setAction(GuardService.ACTION_START),
                )
            }
        }
        guardActive = enabled
        guardFixes = p.getInt(GuardService.PREF_FIXES, 0)
    }

    fun setGuard(on: Boolean) {
        activity.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(GuardService.PREF_ENABLED, on).apply()
        if (on) {
            // 首次开启：没有快照的键以当前系统值建快照——
            // 守护先尊重现状，推荐值只在"一键修复"/手动拨动后生效。
            val p = activity.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
            (SecureSettings.items + SecureSettings.listItems).forEach { item ->
                if (!p.contains(GuardService.SNAP_PREFIX + item.key)) {
                    values[item.key]?.let { saveSnap(item, it) }
                }
            }
            activity.startForegroundService(
                android.content.Intent(activity, GuardService::class.java)
                    .setAction(GuardService.ACTION_START),
            )
        } else {
            activity.startService(
                android.content.Intent(activity, GuardService::class.java)
                    .setAction(GuardService.ACTION_STOP),
            )
        }
        guardActive = on
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        (SecureSettings.items + SecureSettings.listItems).forEach {
            values[it.key] = SecureSettings.read(resolver, it)
        }
        secureWritable = activity.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        ShizukuHelper.refresh()
        refreshGuard()
    }

    /** 更新守护快照——守护服务以快照为准：先存快照再写值，避免竞态。 */
    private fun saveSnap(item: WatchItem, value: String?) {
        activity.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
            .edit().putString(GuardService.SNAP_PREFIX + item.key, value).apply()
    }

    suspend fun write(item: WatchItem, value: String): Boolean = withContext(Dispatchers.IO) {
        saveSnap(item, value)
        val ok = SecureSettings.write(resolver, item, value)
        if (!ok) {
            saveSnap(item, SecureSettings.read(resolver, item))
        }
        values[item.key] = SecureSettings.read(resolver, item)
        ok
    }

    suspend fun fix(item: WatchItem): Boolean = write(item, item.expected)

    suspend fun fixAll(): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var ok = 0
        var fail = 0
        (SecureSettings.items + SecureSettings.listItems).forEach { item ->
            if (item.isOk(values[item.key])) return@forEach
            saveSnap(item, item.expected)
            if (SecureSettings.write(resolver, item, item.expected)) ok++ else {
                saveSnap(item, SecureSettings.read(resolver, item))
                fail++
            }
            values[item.key] = SecureSettings.read(resolver, item)
        }
        ok to fail
    }

    suspend fun writeForbidden(item: WatchItem, entries: List<ForbiddenEntry>): Boolean =
        write(item, SecureSettings.serializeForbidden(entries))

    /**
     * 每次进入黑名单页都调用：把非"允许/询问"的读取应用列表 op 重置为
     * 询问态 ask（需 Shizuku，会在支持重弹的 MIUI 版本上重弹授权框），
     * 然后查询包列表并建索引；拒绝后 appListDenied 驱动提示卡片。
     */
    suspend fun prepareHashIndex() = withContext(Dispatchers.IO) {
        val modeBefore = SecureSettings.appListOpMode(activity)
        if (modeBefore != 0 && modeBefore != 5 &&
            ShizukuHelper.status.value == ShizukuStatus.READY
        ) {
            ShizukuHelper.exec(
                "appops", "set", activity.packageName,
                SecureSettings.MIUI_OP_GET_INSTALLED_APPS.toString(), "ask",
            )
        }
        indexing = true
        hashIndex = SecureSettings.buildHashIndex(activity)
        indexing = false
        val modeAfter = SecureSettings.appListOpMode(activity)
        if (modeBefore != 0 && modeAfter == 0) {
            hashIndex = SecureSettings.buildHashIndex(activity)
        }
        appListDenied = modeAfter != 0
    }

    /** 跳 MIUI 应用权限编辑页（直达"获取应用列表"），不可达则退回应用详情页。 */
    fun openAppDetails() {
        val miui = android.content.Intent("miui.intent.action.APP_PERM_EDITOR")
            .putExtra("extra_pkgname", activity.packageName)
            .putExtra("extra_package_uid", android.os.Process.myUid())
        val fallback = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + activity.packageName),
        )
        runCatching { activity.startActivity(miui) }
            .onFailure { activity.startActivity(fallback) }
    }

    suspend fun grantWriteSecureSettings(): Boolean =
        ShizukuHelper.exec(
            "pm", "grant", activity.packageName,
            "android.permission.WRITE_SECURE_SETTINGS",
        ).ok.also {
            secureWritable = activity.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }
}

class MainActivity : ComponentActivity() {
    private val monitor by lazy { MonitorState(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ShizukuHelper.attach()
        setContent {
            val controller = remember {
                ThemeController(ColorSchemeMode.System)
            }
            MiuixTheme(controller = controller) {
                AppContent(monitor)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ShizukuHelper.refresh()
        lifecycleScope.launch { monitor.refresh() }
    }
}

@Composable
private fun AppContent(state: MonitorState) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(Page.Main) }

    fun snack(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    BackHandler(enabled = page == Page.Blacklist) { page = Page.Main }

    when (page) {
        Page.Main -> MainPage(
            state = state,
            snackbarHostState = snackbarHostState,
            onOpenBlacklist = { page = Page.Blacklist },
            snack = ::snack,
        )
        Page.Blacklist -> BlacklistPage(
            state = state,
            snackbarHostState = snackbarHostState,
            onBack = { page = Page.Main },
            snack = ::snack,
        )
    }
}

@Composable
private fun MainPage(
    state: MonitorState,
    snackbarHostState: SnackbarHostState,
    onOpenBlacklist: () -> Unit,
    snack: (String) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullToRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()
    val shizuku by ShizukuHelper.status.collectAsState()
    var refreshing by remember { mutableStateOf(false) }

    fun doRefresh() {
        refreshing = true
        scope.launch {
            state.refresh()
            refreshing = false
        }
    }

    fun writeItem(item: WatchItem, on: Boolean, okMsg: String) {
        scope.launch {
            state.busy = true
            val ok = state.write(item, if (on) item.expected else item.restore)
            state.busy = false
            snack(if (ok) okMsg else "写入失败，需要 Shizuku 授权")
        }
    }

    val forbiddenCount =
        SecureSettings.parseForbidden(state.values[SecureSettings.forbiddenKey.key]).size
    val offlineCount =
        SecureSettings.parseForbidden(state.values[SecureSettings.forbiddenOfflineKey.key]).size

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "安装守护",
                scrollBehavior = scrollBehavior,
                actions = { TextButton(text = "刷新", onClick = { doRefresh() }) },
            )
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
    ) { paddingValues ->
        PullToRefresh(
            isRefreshing = refreshing,
            onRefresh = { doRefresh() },
            pullToRefreshState = pullToRefreshState,
            topAppBarScrollBehavior = scrollBehavior,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding() + 16.dp,
                ),
            ) {
                item {
                    ShizukuCard(
                        shizuku = shizuku,
                        secureWritable = state.secureWritable,
                        busy = state.busy,
                        onRequest = { ShizukuHelper.requestPermission() },
                        onGrant = {
                            scope.launch {
                                state.busy = true
                                val ok = state.grantWriteSecureSettings()
                                state.busy = false
                                snack(if (ok) "WRITE_SECURE_SETTINGS 已授予" else "授予失败")
                            }
                        },
                    )
                }

                item {
                    GuardCard(
                        active = state.guardActive,
                        fixes = state.guardFixes,
                        busy = state.busy,
                        onToggle = { on -> state.setGuard(on) },
                    )
                }

                item { SmallTitle(text = "安装审查防护（开 = 放行 / 关 = 系统默认）") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        SecureSettings.items.forEach { item ->
                            ProtectionRow(
                                item = item,
                                current = state.values[item.key],
                                busy = state.busy,
                                onWrite = { it, on ->
                                    writeItem(
                                        it, on,
                                        if (on) "已防护：${it.title}" else "已恢复默认：${it.title}",
                                    )
                                },
                            )
                        }
                    }
                }

                item { SmallTitle(text = "安全审核黑名单") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = "审核黑名单详情",
                            summary = if (forbiddenCount + offlineCount == 0) {
                                "已清空 · 云端重新同步后会再次出现"
                            } else {
                                "共 ${forbiddenCount + offlineCount} 条（云端 $forbiddenCount · 离线缓存 $offlineCount），点击查看解析"
                            },
                            onClick = onOpenBlacklist,
                        )
                    }
                }

                item {
                    Button(
                        onClick = {
                            scope.launch {
                                state.busy = true
                                val (ok, fail) = state.fixAll()
                                state.busy = false
                                snack(
                                    if (fail == 0) "全部修复完成（$ok 项）"
                                    else "修复 $ok 项，失败 $fail 项（需要 Shizuku 授权）",
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .padding(top = 16.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        enabled = !state.busy,
                    ) {
                        Text("一键修复全部")
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun BlacklistPage(
    state: MonitorState,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    snack: (String) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val scope = rememberCoroutineScope()
    val shizuku by ShizukuHelper.status.collectAsState()

    LaunchedEffect(Unit) { state.prepareHashIndex() }

    val sources = listOf(
        SecureSettings.forbiddenKey to "云端黑名单",
        SecureSettings.forbiddenOfflineKey to "离线缓存",
    ).map { (item, label) ->
        Triple(item, label, SecureSettings.parseForbidden(state.values[item.key]))
    }
    val total = sources.sumOf { it.third.size }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "安全审核黑名单",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(state = snackbarHostState) },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 16.dp,
            ),
        ) {
            item {
                Card(
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(top = 8.dp),
                ) {
                    BasicComponent(
                        title = "共 $total 条记录",
                        summary = "来源：${SecureSettings.forbiddenKey.ns.cli}/" +
                            "${SecureSettings.forbiddenKey.key} 及其离线缓存键。\n" +
                            "key / value 均为 SHA-256 哈希，用于识别被禁应用的" +
                            "包名、签名或安装包；命中已安装应用时会标注出来。",
                    )
                }
            }

            if (state.appListDenied) {
                item {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 8.dp),
                    ) {
                        BasicComponent(
                            title = "应用列表权限被拒",
                            summary = "已拒绝读取应用列表权限，无法识别条目对应应用",
                            endActions = {
                                TextButton(
                                    text = "去开启",
                                    onClick = { state.openAppDetails() },
                                )
                            },
                        )
                    }
                }
            }

            sources.forEach { (item, label, entries) ->
                item { SmallTitle(text = "$label · ${entries.size} 条") }
                if (entries.isEmpty()) {
                    item {
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            BasicComponent(
                                title = "空",
                                summary = "当前无记录（云端可能稍后重新同步）",
                            )
                        }
                    }
                } else {
                    items(entries.size) { i ->
                        val entry = entries[i]
                        ForbiddenCard(
                            index = i + 1,
                            entry = entry,
                            index_ = state.hashIndex,
                            indexing = state.indexing,
                            busy = state.busy,
                            onRemove = {
                                scope.launch {
                                    state.busy = true
                                    val next = entries.toMutableList().apply { removeAt(i) }
                                    val ok = state.writeForbidden(item, next)
                                    state.busy = false
                                    snack(if (ok) "已移除该条" else "移除失败，需要 Shizuku 授权")
                                }
                            },
                        )
                    }
                }
            }

            item {
                Button(
                    onClick = {
                        scope.launch {
                            state.busy = true
                            var fails = 0
                            sources.forEach { (item, _, entries) ->
                                if (entries.isNotEmpty() &&
                                    !state.writeForbidden(item, emptyList())
                                ) fails++
                            }
                            state.busy = false
                            snack(if (fails == 0) "黑名单已清空" else "部分清空失败，需要 Shizuku 授权")
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 16.dp),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = !state.busy && total > 0,
                ) {
                    Text("清空全部黑名单")
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ForbiddenCard(
    index: Int,
    entry: ForbiddenEntry,
    index_: Map<String, String>?,
    indexing: Boolean,
    busy: Boolean,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "记录 $index",
                style = MiuixTheme.textStyles.title4,
            )
            HashLine(label = "key", hash = entry.key, hit = index_?.get(entry.key))
            HashLine(label = "value", hash = entry.value, hit = index_?.get(entry.value))
            Text(
                text = when {
                    index_?.get(entry.key) != null -> "key 命中：${index_.getValue(entry.key)}"
                    index_?.get(entry.value) != null -> "value 命中：${index_.getValue(entry.value)}"
                    indexing -> "正在扫描已安装应用生成哈希索引…"
                    index_ == null -> "未建立索引"
                    else -> "未命中任何已安装应用（可能针对未安装的应用，或哈希口径不同）"
                },
                style = MiuixTheme.textStyles.footnote1,
                color = if (index_?.get(entry.key) != null || index_?.get(entry.value) != null) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(
                text = "移除此条",
                onClick = onRemove,
                enabled = !busy,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun HashLine(label: String, hash: String, hit: String?) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = "$label${if (hit != null) "（$hit）" else ""}",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Text(
            text = hash.chunked(8).joinToString(" "),
            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
            color = if (hit != null) {
                MiuixTheme.colorScheme.primary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantActions
            },
        )
    }
}

/**
 * Fila de protección: BasicComponent (título + descripción gris + Switch)
 * con línea de estado semántica coloreada debajo, distinguible de la
 * descripción — primary si está protegido, error si fue revertido.
 */
@Composable
private fun ProtectionRow(
    item: WatchItem,
    current: String?,
    busy: Boolean,
    onWrite: (WatchItem, Boolean) -> Unit,
) {
    val ok = item.isOk(current)
    Column {
        BasicComponent(
            title = item.title,
            summary = item.summary,
            endActions = {
                Switch(
                    checked = ok,
                    onCheckedChange = { on -> onWrite(item, on) },
                    enabled = !busy,
                )
            },
        )
        Text(
            text = item.statusText(current),
            style = MiuixTheme.textStyles.footnote1,
            color = if (ok) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.error,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
    }
}

/**
 * 后台守护卡片：ContentObserver 监听所有受监视键。
 * 云端把某个键改回去时，服务立即写回防护值。
 */
@Composable
private fun GuardCard(
    active: Boolean,
    fixes: Int,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
    ) {
        BasicComponent(
            title = "守护自动修复",
            summary = "云端改回受保护设置时自动恢复",
            endActions = {
                Switch(
                    checked = active,
                    onCheckedChange = onToggle,
                    enabled = !busy,
                )
            },
        )
        Text(
            text = if (active) {
                "已激活 · 已自动修复回滚 $fixes 次"
            } else {
                "未激活 · 为确保后台守护持续生效，请在系统设置中" +
                    "允许自启动并关闭电池优化"
            },
            style = MiuixTheme.textStyles.footnote1,
            color = if (active) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onSurfaceVariantActions,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun ShizukuCard(
    shizuku: ShizukuStatus,
    secureWritable: Boolean,
    busy: Boolean,
    onRequest: () -> Unit,
    onGrant: () -> Unit,
) {
    val accent = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantSummary
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 8.dp),
    ) {
        BasicComponent(
            title = "Shizuku",
            summary = when (shizuku) {
                ShizukuStatus.UNAVAILABLE -> "未运行 —— 请先打开 Shizuku 应用启动服务"
                ShizukuStatus.NEED_AUTH -> "已运行，点击请求授权"
                ShizukuStatus.READY -> "已授权，shell 权限可用"
            },
            endActions = {
                Text(
                    text = when (shizuku) {
                        ShizukuStatus.UNAVAILABLE -> "未运行"
                        ShizukuStatus.NEED_AUTH -> "待授权"
                        ShizukuStatus.READY -> "就绪"
                    },
                    color = if (shizuku == ShizukuStatus.READY) accent else secondary,
                )
            },
            onClick = if (shizuku == ShizukuStatus.NEED_AUTH) onRequest else null,
        )
        if (shizuku == ShizukuStatus.READY) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            BasicComponent(
                title = "直写权限",
                summary = if (secureWritable) {
                    "WRITE_SECURE_SETTINGS 已授予，读写可脱离 Shizuku"
                } else {
                    "授予后写入不再依赖 Shizuku（pm grant）"
                },
                endActions = {
                    Text(
                        text = if (secureWritable) "已授予" else "授予",
                        color = if (secureWritable) secondary else accent,
                    )
                },
                enabled = !busy && !secureWritable,
                onClick = { onGrant() },
            )
        }
    }
}
