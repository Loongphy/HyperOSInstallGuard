# HyperOSInstallGuard（安装守护）

HyperOS 安装审查防护工具：把小米安装器的一系列审查/上传/扫描开关拨到「放行/关闭」值，并用前台服务持续监视——云端把这些设置回滚时自动写回。

## 功能

- **安装审查防护**：监视 10 个 `settings` 键（global/secure/system），涵盖包验证器、验证确认框、ADB 安装验证与确认、APK 信息上传、未知来源开关、完整安全审查、安全警告、安装病毒扫描等，全部保持防护值
- **安全审核黑名单**：`packageinstaller_forbidden_app_list` 及其离线缓存键保持为空；黑名单页可解析哈希条目对应的已安装应用（包名/签名/APK 文件 SHA-256），支持单条移除与一键清空
- **后台守护**：`ContentObserver` 事件驱动监听 + 5 分钟兜底扫描，回滚自动修复；开机自启恢复（`BootReceiver`）
- **写入路径**：优先 `WRITE_SECURE_SETTINGS` 直写，失败时经 Shizuku `settings put` 兜底（覆盖 system 命名空间等直写无权限的键）

## 推荐设置（重要）

HyperOS 的 GreezeManager 会冻结后台进程，冻结期间 observer 回调排队，回滚修复会延迟到进程解冻后才执行。为让守护尽快生效：

- **省电策略设为「无限制」**：设置 → 应用设置 → 应用管理 → HyperOSInstallGuard → 省电策略 → 无限制（同时在系统设置的电池优化里将其设为不优化）
- **允许自启动**：设置 → 应用设置 → 授权管理 → 自启动管理 → 开启 HyperOSInstallGuard
- **锁定最近任务**：最近任务界面长按 HyperOSInstallGuard 卡片 → 锁定

未做以上设置时守护仍然有效，但修复可能延迟（实测冻结状态下需等待解冻事件）。

## 权限

- `WRITE_SECURE_SETTINGS`：可用 `adb shell pm grant com.loongphy.installguard android.permission.WRITE_SECURE_SETTINGS` 授予，或在应用内经 Shizuku 一键授予
- Shizuku：未授予直写权限时所有写入经 Shizuku shell 执行；system 命名空间键（如 `virus_scan_install`）始终依赖 Shizuku 兜底

## 构建

Windows 侧构建（需要本机 Android SDK + Android Studio JBR）：

```powershell
.\build.ps1   # gradlew assembleDebug
```
