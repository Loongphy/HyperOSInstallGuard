package com.loongphy.installguard

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Scan
import top.yukonga.miuix.kmp.nav.core.NavController
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

sealed interface AppRoute : NavKey {
    data object Main : AppRoute
    data object Blacklist : AppRoute
}

class MonitorState(private val activity: ComponentActivity) {
    val values = mutableStateMapOf<String, String?>()
    var initialLoaded by mutableStateOf(false)
    var secureWritable by mutableStateOf(false)
    var batteryUnrestricted by mutableStateOf(true)
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
            runCatching {
                activity.startForegroundService(
                    Intent(activity, GuardService::class.java)
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
            val p = activity.getSharedPreferences(GuardService.PREFS, Context.MODE_PRIVATE)
            (SecureSettings.items + SecureSettings.listItems).forEach { item ->
                if (!p.contains(GuardService.SNAP_PREFIX + item.key)) {
                    values[item.key]?.let { saveSnap(item, it) }
                }
            }
            activity.startForegroundService(
                Intent(activity, GuardService::class.java)
                    .setAction(GuardService.ACTION_START),
            )
        } else {
            activity.startService(
                Intent(activity, GuardService::class.java)
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
        val pm = activity.getSystemService(Context.POWER_SERVICE) as? PowerManager
        batteryUnrestricted = pm?.isIgnoringBatteryOptimizations(activity.packageName) ?: true
        ShizukuHelper.refresh()
        refreshGuard()
        initialLoaded = true
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

    /** 跳本应用的系统「应用信息」页（HyperOS 下该页直接提供「自启动」开关与「省电策略」）。 */
    fun openAppInfo() {
        val info = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null),
        )
        runCatching { activity.startActivity(info) }
    }

    /** 跳 MIUI 应用权限编辑页（直达"获取应用列表"），不可达则退回应用信息页。 */
    fun openAppDetails() {
        val miui = Intent("miui.intent.action.APP_PERM_EDITOR")
            .putExtra("extra_pkgname", activity.packageName)
            .putExtra("extra_package_uid", Process.myUid())
        runCatching { activity.startActivity(miui) }
            .onFailure { openAppInfo() }
    }

    fun requestIgnoreBatteryOptimizations() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:" + activity.packageName),
        )
        runCatching { activity.startActivity(direct) }
            .onFailure { openAppInfo() }
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
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
    val backStack = remember { navBackStackOf(AppRoute.Main) }
    val navController = remember { NavController(backStack) }

    NavDisplay(navController = navController) {
        entry<AppRoute.Main> {
            MainPage(
                state = state,
                onOpenBlacklist = {
                    if (navController.backStack.lastOrNull() != AppRoute.Blacklist) {
                        navController.push(AppRoute.Blacklist)
                    }
                },
            )
        }
        entry<AppRoute.Blacklist>(swipeDismiss = NavSwipeDirection.LeftToRight) {
            BlacklistPage(
                state = state,
                onBack = { navController.pop() },
            )
        }
    }
}

@Composable
private fun MiuixFrostedTopBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    backdrop: Backdrop,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val surfaceColor = MiuixTheme.colorScheme.surface
    val blurSupported = isRuntimeShaderSupported()
    val blurColors = BlurDefaults.blurColors(
        blendColors = listOf(BlendColorEntry(surfaceColor.copy(alpha = 0.86f))),
    )
    TopAppBar(
        title = title,
        modifier = if (blurSupported) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 60f,
                colors = blurColors,
            )
        } else {
            Modifier
        },
        color = if (blurSupported) Color.Transparent else surfaceColor,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun MainPage(
    state: MonitorState,
    onOpenBlacklist: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullToRefreshState = rememberPullToRefreshState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val shizuku by ShizukuHelper.status.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    var showKeepAliveSheet by remember { mutableStateOf(false) }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    fun snack(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun doRefresh() {
        refreshing = true
        scope.launch {
            state.refresh()
            refreshing = false
        }
    }

    fun writeItem(item: WatchItem, on: Boolean) {
        scope.launch {
            state.busy = true
            val ok = state.write(item, if (on) item.expected else item.restore)
            state.busy = false
            snack(
                if (ok) {
                    if (on) "已开启防护：${item.title}" else "已恢复系统默认：${item.title}"
                } else {
                    "写入失败，需要 Shizuku 授权"
                },
            )
        }
    }

    fun doFixAll() {
        scope.launch {
            state.busy = true
            val (ok, fail) = state.fixAll()
            state.busy = false
            snack(
                when {
                    fail == 0 && ok > 0 -> "已修复全部 $ok 项防护"
                    fail == 0 -> "所有防护项均已就绪"
                    else -> "已修复 $ok 项，失败 $fail 项（需 Shizuku 授权）"
                },
            )
        }
    }

    val forbiddenCount =
        SecureSettings.parseForbidden(state.values[SecureSettings.forbiddenKey.key]).size
    val offlineCount =
        SecureSettings.parseForbidden(state.values[SecureSettings.forbiddenOfflineKey.key]).size
    val totalForbidden = forbiddenCount + offlineCount

    val allWatched = SecureSettings.items + SecureSettings.listItems
    val totalCount = allWatched.size
    val protectedCount = if (state.initialLoaded) {
        allWatched.count { it.isOk(state.values[it.key]) }
    } else {
        totalCount
    }
    val issueCount = (totalCount - protectedCount).coerceAtLeast(0)

    val reviewItems = remember {
        SecureSettings.items.filter { it.category == WatchCategory.INSTALL_REVIEW }
    }
    val adbPrivacyItems = remember {
        SecureSettings.items.filter { it.category == WatchCategory.ADB_PRIVACY }
    }
    val internalStateItems = remember {
        SecureSettings.items.filter { it.category == WatchCategory.INTERNAL_STATE }
    }

    Scaffold(
        topBar = {
            MiuixFrostedTopBar(
                title = "安装守护",
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                actions = {
                    IconButton(onClick = { showKeepAliveSheet = true }) {
                        Icon(
                            imageVector = MiuixIcons.Info,
                            contentDescription = "后台保活建议",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                    IconButton(
                        onClick = { doRefresh() },
                        enabled = !refreshing,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Refresh,
                            contentDescription = "刷新",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
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
                    .layerBackdrop(backdrop)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding() + 4.dp,
                    bottom = paddingValues.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item {
                    ProtectionOverviewCard(
                        protectedCount = protectedCount,
                        totalCount = totalCount,
                        issueCount = issueCount,
                        guardActive = state.guardActive,
                        busy = state.busy,
                        onFixAll = { doFixAll() },
                    )
                }

                item { SmallTitle(text = "运行环境与守护") }
                item {
                    EnvironmentGuardCard(
                        shizuku = shizuku,
                        secureWritable = state.secureWritable,
                        batteryUnrestricted = state.batteryUnrestricted,
                        guardActive = state.guardActive,
                        guardFixes = state.guardFixes,
                        busy = state.busy,
                        onToggleGuard = { state.setGuard(it) },
                        onRequestShizuku = { ShizukuHelper.requestPermission() },
                        onGrantSecure = {
                            scope.launch {
                                state.busy = true
                                val ok = state.grantWriteSecureSettings()
                                state.busy = false
                                snack(if (ok) "WRITE_SECURE_SETTINGS 已授予" else "授权失败")
                            }
                        },
                        onOpenKeepAlive = { showKeepAliveSheet = true },
                    )
                }

                item { SmallTitle(text = "安装审查与未知来源") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        reviewItems.forEach { item ->
                            ProtectionSwitchRow(
                                item = item,
                                current = state.values[item.key],
                                initialLoaded = state.initialLoaded,
                                busy = state.busy,
                                onToggle = { on -> writeItem(item, on) },
                            )
                        }
                    }
                }

                item { SmallTitle(text = "ADB 与云端隐私") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        adbPrivacyItems.forEach { item ->
                            ProtectionSwitchRow(
                                item = item,
                                current = state.values[item.key],
                                initialLoaded = state.initialLoaded,
                                busy = state.busy,
                                onToggle = { on -> writeItem(item, on) },
                            )
                        }
                    }
                }

                item { SmallTitle(text = "名单与内部状态") }
                item {
                    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                        ArrowPreference(
                            title = "安全审核黑名单",
                            summary = if (totalForbidden == 0) {
                                "云端与离线缓存均已清空"
                            } else {
                                "云端 $forbiddenCount 条 · 离线缓存 $offlineCount 条，点击解析"
                            },
                            summaryColor = if (totalForbidden == 0) {
                                BasicComponentDefaults.summaryColor()
                            } else {
                                BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
                            },
                            endActions = {
                                Text(
                                    text = if (totalForbidden == 0) "已清空" else "$totalForbidden 条",
                                    style = MiuixTheme.textStyles.body2,
                                    color = if (totalForbidden == 0) {
                                        MiuixTheme.colorScheme.onSurfaceVariantActions
                                    } else {
                                        MiuixTheme.colorScheme.error
                                    },
                                )
                            },
                            onClick = onOpenBlacklist,
                        )
                        internalStateItems.forEach { item ->
                            ProtectionSwitchRow(
                                item = item,
                                current = state.values[item.key],
                                initialLoaded = state.initialLoaded,
                                busy = state.busy,
                                onToggle = { on -> writeItem(item, on) },
                            )
                        }
                    }
                }
            }
        }

        KeepAliveBottomSheet(
            show = showKeepAliveSheet,
            batteryUnrestricted = state.batteryUnrestricted,
            onDismiss = { showKeepAliveSheet = false },
            onRequestBattery = { state.requestIgnoreBatteryOptimizations() },
            onOpenAppInfo = { state.openAppInfo() },
        )
    }
}

@Composable
private fun ProtectionOverviewCard(
    protectedCount: Int,
    totalCount: Int,
    issueCount: Int,
    guardActive: Boolean,
    busy: Boolean,
    onFixAll: () -> Unit,
) {
    val allOk = issueCount == 0
    val accentColor = if (allOk) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (allOk) MiuixIcons.Ok else MiuixIcons.Scan,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp),
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 8.dp),
            ) {
                Text(
                    text = if (allOk) "全部防护已生效" else "发现 $issueCount 项待修复",
                    style = MiuixTheme.textStyles.title3,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = buildString {
                        append("已放行 $protectedCount/$totalCount 项")
                        append(if (guardActive) " · 守护已开启" else " · 守护未开启")
                    },
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }

            if (allOk) {
                StatusTag(
                    text = "已就绪",
                    color = MiuixTheme.colorScheme.primary,
                )
            } else {
                TextButton(
                    text = "一键修复",
                    onClick = onFixAll,
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusTag(
    text: String,
    color: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun EnvironmentGuardCard(
    shizuku: ShizukuStatus,
    secureWritable: Boolean,
    batteryUnrestricted: Boolean,
    guardActive: Boolean,
    guardFixes: Int,
    busy: Boolean,
    onToggleGuard: (Boolean) -> Unit,
    onRequestShizuku: () -> Unit,
    onGrantSecure: () -> Unit,
    onOpenKeepAlive: () -> Unit,
) {
    val primary = MiuixTheme.colorScheme.primary
    val secondary = MiuixTheme.colorScheme.onSurfaceVariantActions

    Card(modifier = Modifier.padding(horizontal = 12.dp)) {
        SwitchPreference(
            title = "后台自动守护",
            summary = if (guardActive) {
                "监听设置变更 · 已自动恢复回滚 $guardFixes 次"
            } else {
                "云端改回受保护设置时立即自动写回"
            },
            checked = guardActive,
            onCheckedChange = onToggleGuard,
            enabled = !busy,
        )

        if (shizuku == ShizukuStatus.NEED_AUTH) {
            ArrowPreference(
                title = "Shizuku 服务",
                summary = "服务已运行，点击请求 Shell 授权",
                endActions = {
                    Text(
                        text = "待授权",
                        style = MiuixTheme.textStyles.body2,
                        color = primary,
                    )
                },
                onClick = onRequestShizuku,
            )
        } else {
            BasicComponent(
                title = "Shizuku 服务",
                summary = when (shizuku) {
                    ShizukuStatus.READY -> "Shell 通道就绪，覆盖受限系统命名空间"
                    ShizukuStatus.UNAVAILABLE -> "未运行 · 打开 Shizuku 启动服务后可用"
                    ShizukuStatus.NEED_AUTH -> ""
                },
                endActions = {
                    Text(
                        text = if (shizuku == ShizukuStatus.READY) "已就绪" else "未运行",
                        style = MiuixTheme.textStyles.body2,
                        color = if (shizuku == ShizukuStatus.READY) primary else secondary,
                    )
                },
            )
        }

        if (!secureWritable && shizuku == ShizukuStatus.READY) {
            ArrowPreference(
                title = "安全设置直写",
                summary = "点击通过 Shizuku 授予 WRITE_SECURE_SETTINGS",
                endActions = {
                    Text(
                        text = "一键授予",
                        style = MiuixTheme.textStyles.body2,
                        color = primary,
                    )
                },
                enabled = !busy,
                onClick = onGrantSecure,
            )
        } else {
            BasicComponent(
                title = "安全设置直写",
                summary = if (secureWritable) {
                    "已授予直写权限，常规项可脱离 Shizuku"
                } else {
                    "未授予 · 授权 Shizuku 后可一键开启"
                },
                endActions = {
                    Text(
                        text = if (secureWritable) "已授予" else "未授予",
                        style = MiuixTheme.textStyles.body2,
                        color = secondary,
                    )
                },
            )
        }

        if (guardActive && !batteryUnrestricted) {
            ArrowPreference(
                title = "后台省电策略",
                summary = "设为「无限制」并允许自启动以防冻结",
                endActions = {
                    Text(
                        text = "去优化",
                        style = MiuixTheme.textStyles.body2,
                        color = primary,
                    )
                },
                onClick = onOpenKeepAlive,
            )
        }
    }
}

@Composable
private fun ProtectionSwitchRow(
    item: WatchItem,
    current: String?,
    initialLoaded: Boolean,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val ok = !initialLoaded || item.isOk(current)
    SwitchPreference(
        title = item.title,
        summary = if (ok) item.summary else item.statusText(current),
        summaryColor = if (ok) {
            BasicComponentDefaults.summaryColor()
        } else {
            BasicComponentDefaults.summaryColor(color = MiuixTheme.colorScheme.error)
        },
        checked = ok,
        onCheckedChange = onToggle,
        enabled = !busy,
    )
}

@Composable
private fun KeepAliveBottomSheet(
    show: Boolean,
    batteryUnrestricted: Boolean,
    onDismiss: () -> Unit,
    onRequestBattery: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = "后台保活建议",
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.padding(bottom = 16.dp)) {
            Text(
                text = "HyperOS 的后台冻结机制会延缓设置变更回调。完成以下三项配置后，云端回滚可在第一时间自动修复：",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Card(
                colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                    color = MiuixTheme.colorScheme.surface,
                ),
            ) {
                if (batteryUnrestricted) {
                    BasicComponent(
                        title = "1. 省电策略设为无限制",
                        summary = "已忽略电池优化，可在应用信息页确认设为「无限制」",
                        endActions = {
                            Text(
                                text = "已开启",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = onOpenAppInfo,
                    )
                } else {
                    ArrowPreference(
                        title = "1. 省电策略设为无限制",
                        summary = "关闭电池优化并在应用信息页将省电策略改为「无限制」",
                        endActions = {
                            Text(
                                text = "去设置",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = onRequestBattery,
                    )
                }

                ArrowPreference(
                    title = "2. 允许应用自启动",
                    summary = "跳转应用信息页，直接开启「自启动」开关",
                    endActions = {
                        Text(
                            text = "去开启",
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.primary,
                        )
                    },
                    onClick = onOpenAppInfo,
                )

                BasicComponent(
                    title = "3. 锁定最近任务卡片",
                    summary = "在系统最近任务界面长按「安装守护」卡片并点击锁定图标",
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(
                text = "知道了",
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}

@Composable
private fun BlacklistPage(
    state: MonitorState,
    onBack: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    val pullToRefreshState = rememberPullToRefreshState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }

    val surfaceColor = MiuixTheme.colorScheme.surface
    val backdrop = rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }

    fun snack(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    fun doRefresh() {
        refreshing = true
        scope.launch {
            state.refresh()
            state.prepareHashIndex()
            refreshing = false
        }
    }

    LaunchedEffect(Unit) { state.prepareHashIndex() }

    val sources = listOf(
        SecureSettings.forbiddenKey to "云端黑名单",
        SecureSettings.forbiddenOfflineKey to "离线缓存",
    ).map { (item, label) ->
        Triple(item, label, SecureSettings.parseForbidden(state.values[item.key]))
    }
    val cloudCount = sources[0].third.size
    val offlineCount = sources[1].third.size
    val total = cloudCount + offlineCount

    Scaffold(
        topBar = {
            MiuixFrostedTopBar(
                title = "安全审核黑名单",
                scrollBehavior = scrollBehavior,
                backdrop = backdrop,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = "返回",
                            tint = MiuixTheme.colorScheme.onSurface,
                        )
                    }
                },
                actions = {
                    if (total > 0) {
                        IconButton(
                            onClick = { showClearConfirm = true },
                            enabled = !state.busy,
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Delete,
                                contentDescription = "清空全部",
                                tint = MiuixTheme.colorScheme.error,
                            )
                        }
                    }
                },
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
                    .layerBackdrop(backdrop)
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding() + 4.dp,
                    bottom = paddingValues.calculateBottomPadding() + 24.dp,
                ),
            ) {
                item {
                    BlacklistSummaryCard(
                        total = total,
                        cloudCount = cloudCount,
                        offlineCount = offlineCount,
                        indexing = state.indexing,
                        busy = state.busy,
                        onClearAll = { showClearConfirm = true },
                    )
                }

                if (state.appListDenied) {
                    item {
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(top = 10.dp),
                        ) {
                            ArrowPreference(
                                title = "读取应用列表权限受限",
                                summary = "开启后可自动比对本机已安装应用，定位哈希对应的包名与签名",
                                endActions = {
                                    Text(
                                        text = "去开启",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.primary,
                                    )
                                },
                                onClick = { state.openAppDetails() },
                            )
                        }
                    }
                }

                if (total == 0) {
                    item { SmallTitle(text = "受控键值状态") }
                    item {
                        Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                            BasicComponent(
                                title = "云端审核黑名单",
                                summary = "小米安装器云端同步的禁止安装哈希列表",
                                endActions = {
                                    Text(
                                        text = "已清空",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                },
                            )
                            BasicComponent(
                                title = "离线缓存黑名单",
                                summary = "网络异常时生效的本地缓存禁止安装列表",
                                endActions = {
                                    Text(
                                        text = "已清空",
                                        style = MiuixTheme.textStyles.body2,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                    )
                                },
                            )
                        }
                    }
                } else {
                    sources.forEach { (item, label, entries) ->
                        item { SmallTitle(text = "$label · ${entries.size} 条") }
                        if (entries.isEmpty()) {
                            item {
                                Card(modifier = Modifier.padding(horizontal = 12.dp)) {
                                    BasicComponent(
                                        title = "当前无记录",
                                        summary = "该存储键已为空",
                                    )
                                }
                            }
                        } else {
                            items(entries.size) { i ->
                                val entry = entries[i]
                                ForbiddenCard(
                                    index = i + 1,
                                    entry = entry,
                                    hashIndex = state.hashIndex,
                                    indexing = state.indexing,
                                    busy = state.busy,
                                    onCopyHash = { hash ->
                                        state.copyToClipboard("SHA-256", hash)
                                        snack("已复制 SHA-256 哈希")
                                    },
                                    onRemove = {
                                        scope.launch {
                                            state.busy = true
                                            val next = entries.toMutableList().apply { removeAt(i) }
                                            val ok = state.writeForbidden(item, next)
                                            state.busy = false
                                            snack(if (ok) "已移除该条记录" else "移除失败，需要 Shizuku 授权")
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        OverlayDialog(
            show = showClearConfirm,
            title = "清空安全审核黑名单",
            summary = "将同时清空云端黑名单与离线缓存中的全部 $total 条记录，是否继续？",
            onDismissRequest = { showClearConfirm = false },
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = "取消",
                    onClick = { showClearConfirm = false },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = "确认清空",
                    onClick = {
                        showClearConfirm = false
                        scope.launch {
                            state.busy = true
                            var fails = 0
                            sources.forEach { (item, _, entries) ->
                                if (entries.isNotEmpty() &&
                                    !state.writeForbidden(item, emptyList())
                                ) fails++
                            }
                            state.busy = false
                            snack(if (fails == 0) "黑名单已全部清空" else "部分清空失败，需要 Shizuku 授权")
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

@Composable
private fun BlacklistSummaryCard(
    total: Int,
    cloudCount: Int,
    offlineCount: Int,
    indexing: Boolean,
    busy: Boolean,
    onClearAll: () -> Unit,
) {
    val clean = total == 0
    val accentColor = if (clean) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(top = 4.dp, bottom = 2.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (clean) MiuixIcons.Ok else MiuixIcons.Blocklist,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 14.dp, end = 8.dp),
                ) {
                    Text(
                        text = if (clean) "黑名单已全部清空" else "共拦截 $total 条记录",
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (clean) {
                            "当前无禁止安装记录，开启后台守护后云端下发时会自动清空"
                        } else {
                            "云端 $cloudCount 条 · 离线缓存 $offlineCount 条"
                        },
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }

                if (clean) {
                    StatusTag(text = "无记录", color = MiuixTheme.colorScheme.primary)
                } else {
                    TextButton(
                        text = "清空全部",
                        onClick = onClearAll,
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            if (indexing) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InfiniteProgressIndicator(
                        size = 14.dp,
                        strokeWidth = 1.8.dp,
                        color = MiuixTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "正在扫描本机已安装应用建立 SHA-256 索引…",
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ForbiddenCard(
    index: Int,
    entry: ForbiddenEntry,
    hashIndex: Map<String, String>?,
    indexing: Boolean,
    busy: Boolean,
    onCopyHash: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val keyHit = hashIndex?.get(entry.key)
    val valueHit = hashIndex?.get(entry.value)
    val matchedText = when {
        keyHit != null -> "命中本机：$keyHit"
        valueHit != null -> "命中本机：$valueHit"
        indexing -> "正在比对本机应用哈希…"
        hashIndex == null -> "尚未建立应用哈希索引"
        else -> "未命中本机已安装应用（可能针对未安装应用）"
    }
    val hasHit = keyHit != null || valueHit != null

    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 10.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "记录 #$index",
                    style = MiuixTheme.textStyles.title4,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (hasHit) {
                    Spacer(modifier = Modifier.width(8.dp))
                    StatusTag(
                        text = "已定位应用",
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = onRemove,
                    enabled = !busy,
                    minWidth = 34.dp,
                    minHeight = 34.dp,
                ) {
                    Icon(
                        imageVector = MiuixIcons.Delete,
                        contentDescription = "移除此条",
                        tint = MiuixTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            HashBlock(
                label = "KEY",
                hash = entry.key,
                hit = keyHit,
                onCopy = { onCopyHash(entry.key) },
            )
            HashBlock(
                label = "VALUE",
                hash = entry.value,
                hit = valueHit,
                onCopy = { onCopyHash(entry.value) },
            )

            Text(
                text = matchedText,
                style = MiuixTheme.textStyles.footnote1,
                color = if (hasHit) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

@Composable
private fun HashBlock(
    label: String,
    hash: String,
    hit: String?,
    onCopy: () -> Unit,
) {
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MiuixTheme.colorScheme.surface)
            .clickable(onClick = onCopy)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (hit != null) "$label · $hit" else label,
                style = MiuixTheme.textStyles.footnote2,
                fontWeight = FontWeight.Medium,
                color = if (hit != null) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = MiuixIcons.Copy,
                contentDescription = "复制哈希",
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            text = hash.chunked(8).joinToString(" "),
            style = MiuixTheme.textStyles.footnote1.copy(fontFamily = FontFamily.Monospace),
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
