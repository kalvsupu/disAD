# ============================================================================
#  disAD · 16KB 页 / OCR 运行时验证脚本
# ============================================================================
#  用途：在真机或模拟器（adb 可连）上验证候选包的三件事：
#    ① 设备页大小是不是 16 KB（4096 = 普通设备，16384 = 16KB 设备）
#    ② 16 KB 设备上能否安装并加载 ONNX 原生库（不崩、不 dlopen 失败）
#    ③ OCR 走的是 XNNPACK 还是 CPU 回退，以及模型加载/识别耗时
#
#  用法：
#    powershell -ExecutionPolicy Bypass -File tools\verify-16k.ps1 `
#        -Apk "D:\xxx\disAD-v2.4.0-ORT122-16KB候选.apk"
#
#  可选参数：
#    -Seconds 90        抓日志的时长（默认 90 秒）
#    -SkipInstall       不重装，只抓日志
#
#  说明：
#    · 脚本不会自动去点广告；请在抓日志期间**手动触发一次开屏广告**（开个 App 即可）
#    · 结果会打印到屏幕，并保存到 dist\verify-<时间>.txt
# ============================================================================
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [int]$Seconds = 90,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $repo 'dist'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$report = Join-Path $outDir ("verify-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".txt")

function Say($text, $color = 'Gray') {
    Write-Host $text -ForegroundColor $color
    Add-Content -Path $report -Value $text -Encoding utf8
}

Say "==== disAD 运行时验证 ====" 'Cyan'
Say ("时间： " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
Say ("APK ： " + $Apk)

# ---------- 0. adb ----------
$adbCmd = Get-Command adb -ErrorAction SilentlyContinue
$adb = $null
if ($adbCmd) { $adb = $adbCmd.Source }
if (-not $adb) {
    $sdk = $env:ANDROID_HOME
    if ($sdk) { $cand = Join-Path $sdk 'platform-tools\adb.exe'; if (Test-Path $cand) { $adb = $cand } }
}
if (-not $adb) { Say "找不到 adb。请把 platform-tools 加进 PATH，或设置 ANDROID_HOME。" 'Red'; exit 1 }

$devices = & $adb devices | Select-String -Pattern '\sdevice$'
if (-not $devices) { Say "没有已连接的设备（adb devices 为空，或未授权）。" 'Red'; exit 1 }
Say ("设备： " + ($devices -join ' | '))

# ---------- 1. 设备信息 ----------
Say "`n---- ① 设备与页大小 ----" 'Cyan'
$vendor = (& $adb shell getprop ro.product.manufacturer).Trim()
$model = (& $adb shell getprop ro.product.model).Trim()
$sdkInt = (& $adb shell getprop ro.build.version.sdk).Trim()
$rel = (& $adb shell getprop ro.build.version.release).Trim()
Say ("机型： $vendor $model · Android $rel (SDK $sdkInt)")

# getconf 在部分 ROM 上不完整，三条路都试
$page = ''
try { $page = (& $adb shell getconf PAGE_SIZE).Trim() } catch { }
if (-not $page -or $page -notmatch '^\d+$') {
    try { $page = (& $adb shell "cat /proc/self/smaps 2>/dev/null | head -1").Trim() } catch { }
}
if (-not $page -or $page -notmatch '^\d+$') {
    # 兜底：看内核配置
    try {
        $txt = (& $adb shell "zcat /proc/config.gz 2>/dev/null | grep -i 'CONFIG_PAGE_SIZE'") -join ' '
        if ($txt -match '16384|16K') { $page = '16384' } elseif ($txt -match '4096|4K') { $page = '4096' }
    } catch { }
}
if ($page -match '^\d+$') {
    if ([int]$page -eq 16384) {
        Say ("页大小： $page 字节 → 16 KB 页设备 ✅（这是能真正验证 16KB 的机器）") 'Green'
    } else {
        Say ("页大小： $page 字节 → 普通 4 KB 页设备（此机上无法验证 16KB 加载，只能验证功能与性能）") 'Yellow'
    }
} else {
    Say "页大小： 读取失败（该 ROM 限制了 getconf / proc），请用 16 KB 模拟器镜像或换一台机器。" 'Yellow'
}

# ---------- 2. 安装 ----------
if (-not $SkipInstall) {
    Say "`n---- ② 安装候选包 ----" 'Cyan'
    $r = & $adb install -r -d $Apk 2>&1
    Say ("adb install: " + ($r -join ' '))
    if ($r -match 'Failure|失败|INSTALL_FAILED') {
        Say "安装失败。若是 16 KB 设备报 INSTALL_FAILED_INVALID_APK / 与 .so 相关，说明对齐或签名有问题。" 'Red'
        exit 1
    }
    if ($r -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        Say "签名与已装版本不一致，需要先卸载：adb uninstall com.disad" 'Yellow'
    }
}

$ver = (& $adb shell dumpsys package com.disad | Select-String -Pattern 'versionName|versionCode' | Select-Object -First 2) -join ' '
Say ("已装版本： " + $ver)
if ($ver -notmatch '2\.4\.0') { Say "注意：装上的不是 2.4.0 候选包，后续结论可能不适用。" 'Yellow' }

# ---------- 3. 抓日志 ----------
Say "`n---- ③ 抓 OCR / 原生库日志（$Seconds 秒）----" 'Cyan'
Say "现在请在手机上：打开 disAD → 启动 DNS 拦截 + 无障碍跳过 → 随便开一个带开屏广告的 App。"
Say "（也会顺带记录 dlopen / UnsatisfiedLinkError 之类的加载失败）"

& $adb logcat -c | Out-Null
$patterns = 'disAD|xnnpack|XNNPACK|onnxruntime|UnsatisfiedLink|dlopen|OCR|OCR 兜底|/data/app'
$deadline = (Get-Date).AddSeconds($Seconds)
$lines = New-Object System.Collections.Generic.List[string]
$proc = Start-Process -FilePath $adb -ArgumentList 'logcat', '-v', 'time' -RedirectStandardOutput (Join-Path $env:TEMP 'disad-logcat.txt') -PassThru -NoNewWindow
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Milliseconds 500
}
try { $proc.Kill() } catch { }

$raw = Get-Content (Join-Path $env:TEMP 'disad-logcat.txt') -ErrorAction SilentlyContinue
$hits = $raw | Select-String -Pattern $patterns
Say ("抓到相关日志 " + @($hits).Count + " 行")

# ---------- 4. 判定 ----------
Say "`n---- ④ 结论 ----" 'Cyan'

$loadFail = $hits | Select-String -Pattern 'UnsatisfiedLinkError|dlopen failed|cannot locate|is 32-bit instead of 64-bit'
if ($loadFail) {
    Say "❌ 原生库加载失败（这基本就是 16KB 对齐/ABI 问题）：" 'Red'
    $loadFail | Select-Object -First 5 | ForEach-Object { Say ("   " + $_.Line.Trim()) }
} else {
    Say "✅ 没有出现 dlopen / UnsatisfiedLinkError（原生库加载正常）" 'Green'
}

$ready = $hits | Select-String -Pattern 'OCR 就绪'
if ($ready) {
    foreach ($l in $ready) { Say ("   模型就绪日志：" + $l.Line.Trim()) }
    $m = ($ready | Select-Object -Last 1).Line
    if ($m -match '/\s*(xnnpack|cpu)\s*/') { Say ("   执行提供者： " + $Matches[1]) 'Green' }
    if ($m -match '(\d+)ms') { Say ("   模型加载耗时： " + $Matches[1] + " ms") }
} else {
    Say "   ⚠ 没抓到「OCR 就绪」——说明 OCR 没被触发过，或初始化失败（看下面「模型后台初始化」行）" 'Yellow'
}

$init = $hits | Select-String -Pattern 'OCR 模型后台初始化完成'
foreach ($l in $init) { Say ("   初始化：" + $l.Line.Trim()) }

$fallback = $hits | Select-String -Pattern 'XNNPACK 不可用'
if ($fallback) {
    foreach ($l in $fallback) { Say ("   ⚠ XNNPACK 回退到 CPU：" + $l.Line.Trim()) 'Yellow' }
}

$perf = $hits | Select-String -Pattern 'OCR 兜底命中|块都没读到跳过'
if ($perf) {
    Say "`n   识别耗时样本：" 'Cyan'
    foreach ($l in ($perf | Select-Object -Last 8)) { Say ("   " + $l.Line.Trim()) }
} else {
    Say "   没有识别耗时记录（本轮没触发 OCR）" 'Yellow'
}

Say "`n报告已保存： $report"
Say "把这份报告（或 dist 下对应文件）发回即可。" 'Cyan'
