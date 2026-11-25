# HyperOSInstallGuard（安装守护）

HyperOS 安装审查防护工具：把小米安装器的一系列审查/上传/扫描开关拨到「放行/关闭」值，并用前台服务持续监视——云端把这些设置回滚时自动写回。

## 功能

- **安装审查防护**：监视 10 个 `settings` 键（global/secure/system），涵盖包验证器、验证确认框、ADB 安装验证与确认、APK 信息上传、未知来源开关、完整安全审查、安全警告、安装病毒扫描等，全部保持防护值
- **安全审核黑名单**：`packageinstaller_forbidden_app_list` 及其离线缓存键保持为空；黑名单页可解析哈希条目对应的已安装应用（包名/签名/APK 文件 SHA-256），支持单条移除与一键清空
- **后台守护**：`JobScheduler TriggerContentUri` 系统级监听设置键，应用进程被杀后也会由系统拉起 `JobService` 自动修复；前台服务内的 `ContentObserver` 负责存活时的低延迟修复，另有 15 分钟持久化巡检兜底；开机/应用更新后自动重新布防
- **回滚识别**：守护以用户在应用内最近一次设定/修复后的值为快照；系统或云端改回默认值会被自动写回，而用户在应用内主动关闭某项会被视为明确选择，不会被守护强行重新打开
- **写入路径**：优先 `WRITE_SECURE_SETTINGS` 直写，失败时经 Shizuku `settings put` 兜底（覆盖 system 命名空间等直写无权限的键）

## 推荐设置（重要）

系统级 `JobScheduler` 监听不依赖应用进程常驻，是本工具的主要防护路径；前台服务只负责进一步降低延迟。HyperOS 的 GreezeManager 仍可能冻结或延迟前台服务/任务执行，为让守护尽快生效：

- **省电策略设为「无限制」**：设置 → 应用设置 → 应用管理 → HyperOSInstallGuard → 省电策略 → 无限制（同时在系统设置的电池优化里将其设为不优化）
- **允许自启动**：设置 → 应用设置 → 授权管理 → 自启动管理 → 开启 HyperOSInstallGuard
- **锁定最近任务**：最近任务界面长按 HyperOSInstallGuard 卡片 → 锁定

未做以上设置时守护仍然有效，但修复可能延迟（实测冻结状态下需等待解冻事件）。

## 系统边界

普通杀进程、划掉最近任务卡片不会清除 `JobScheduler` 任务；设置项变化仍可由系统冷拉起 `GuardJobService` 修复。但在系统应用信息页执行「强行停止」会按 Android 平台设计清空该应用所有 JobScheduler 任务，这是无法绕过的系统边界；此后需重启手机（`BOOT_COMPLETED` + persisted 巡检任务）或再次打开应用来重新布防。

## 权限

- `WRITE_SECURE_SETTINGS`：可用 `adb shell pm grant com.loongphy.installguard android.permission.WRITE_SECURE_SETTINGS` 授予，或在应用内经 Shizuku 一键授予
- Shizuku：未授予直写权限时所有写入经 Shizuku shell 执行；system 命名空间键（如 `virus_scan_install`）始终依赖 Shizuku 兜底

## 构建

Windows 侧构建（需要本机 Android SDK + Android Studio JBR）：

```powershell
.\build.ps1   # gradlew assembleDebug
```
