# ============================================================================
#  disAD 发布构建脚本（含 16KB 页对齐）
# ============================================================================
#  为什么需要这个脚本：
#    AGP 8.2 打包时只做 4KB 页对齐（zipalign -p 4）。要让未压缩的 .so 满足
#    Android 16KB 页设备的要求，必须再用 build-tools 35 的
#    `zipalign -P 16` 后处理一次；而 zipalign 会清掉签名，所以必须重新签名。
#
#  用法（在仓库根目录）：
#    powershell -ExecutionPolicy Bypass -File tools\build-release.ps1
#
#  前置：
#    · JDK 17
#    · Android SDK：build-tools;35.0.0（16KB 对齐）+ platform 34
#    · local.properties 里配置 sdk.dir，以及本地签名（DEV_STORE_* 四项，可选）
# ============================================================================
$ErrorActionPreference = 'Stop'

$repo = Split-Path -Parent $PSScriptRoot
$sdk = $env:ANDROID_HOME
if (-not $sdk -and (Test-Path (Join-Path $repo 'local.properties'))) {
    $line = Get-Content (Join-Path $repo 'local.properties') |
        Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if ($line) { $sdk = ($line -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\' }
}
if (-not $sdk) { throw "找不到 Android SDK（请设置 ANDROID_HOME 或在 local.properties 里写 sdk.dir）" }

# JDK 17：没设 JAVA_HOME 时回退到本机常见位置，否则 gradle 直接报
# "JAVA_HOME is not set and no 'java' command could be found in your PATH."
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    foreach ($jdk in @('D:\AndroidBuild\jdk17', 'C:\Program Files\Android\Android Studio\jbr')) {
        if (Test-Path (Join-Path $jdk 'bin\java.exe')) {
            $env:JAVA_HOME = $jdk
            Write-Host "   JAVA_HOME：$jdk" -ForegroundColor DarkGray
            break
        }
    }
}
if (-not $env:JAVA_HOME) { throw "找不到 JDK 17（请设置 JAVA_HOME）" }

$bt = Join-Path $sdk 'build-tools\35.0.0'
if (-not (Test-Path (Join-Path $bt 'zipalign.exe'))) {
    throw "需要 build-tools;35.0.0（16KB 对齐）。安装：sdkmanager `"build-tools;35.0.0`""
}

Write-Host "== 1/4 构建 ==" -ForegroundColor Cyan
# AGP 对中文路径不友好：仓库路径含非 ASCII 字符时，优先用 ASCII junction 构建
$buildRoot = $repo
if ($repo -match '[^\x00-\x7F]') {
    foreach ($alt in @('D:\disad')) {
        if (Test-Path (Join-Path $alt 'settings.gradle.kts')) { $buildRoot = $alt; break }
    }
    if ($buildRoot -ne $repo) { Write-Host "   中文路径：改用 $buildRoot 构建" }
}
# 优先用本机已安装的 gradle。gradlew 会去 services.gradle.org 下载发行包，
# 国内网络经常拉不下来；而拉不下来时旧版本脚本会继续往下给**上一次的产物**签名，
# 产出看着成功、其实版本号还是旧的 —— 这个坑已经踩过一次。
# 可用环境变量 GRADLE_BIN 指定，默认走 D:\AndroidBuild\gradle-8.2。
$buildStart = Get-Date
$localGradle = if ($env:GRADLE_BIN) { $env:GRADLE_BIN } else { 'D:\AndroidBuild\gradle-8.2\bin\gradle.bat' }
Push-Location $buildRoot
if (Test-Path $localGradle) {
    Write-Host "   使用本地 gradle：$localGradle"
    & $localGradle assembleRelease --no-daemon --console=plain
} else {
    Write-Host "   本地 gradle 不存在，退回 gradlew（会联网下载发行包）" -ForegroundColor Yellow
    & (Join-Path $repo 'gradlew.bat') assembleRelease --no-daemon --console=plain
}
$buildExit = $LASTEXITCODE
Pop-Location
if ($buildExit -ne 0) { throw "构建失败（退出码 $buildExit）—— 不用旧产物继续签名" }

$built = Join-Path $repo 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $built)) { throw "构建产物不存在：$built" }
# 产物里的版本号必须与 build.gradle.kts 一致，否则说明签名的是旧包
# （gradle 会把未变化的任务标成 UP-TO-DATE，所以不能用文件时间判断）
$gradleFile = Join-Path $repo 'app\build.gradle.kts'
$wantVer = $null
if (Test-Path $gradleFile) {
    $m = [regex]::Match((Get-Content $gradleFile -Raw), 'versionName\s*=\s*"([^"]+)"')
    if ($m.Success) { $wantVer = $m.Groups[1].Value }
}
if ($wantVer) {
    $badge = (& (Join-Path $bt 'aapt2.exe') dump badging $built) | Select-String -Pattern '^package:'
    if (-not ($badge -match "versionName='$([regex]::Escape($wantVer))'")) {
        throw "产物版本与 build.gradle.kts 不一致（期望 $wantVer），拒绝继续签名"
    }
    Write-Host "   版本校验：$wantVer"
}

$outDir = Join-Path $repo 'dist'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$aligned = Join-Path $outDir 'app-release-aligned.apk'
$signed = Join-Path $outDir 'app-release-16k.apk'

Write-Host "== 2/4 16KB 页对齐 ==" -ForegroundColor Cyan
& (Join-Path $bt 'zipalign.exe') -f -P 16 -v 4 $built $aligned | Out-Null
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败（退出码 $LASTEXITCODE）" }

Write-Host "== 3/4 重新签名 ==" -ForegroundColor Cyan
$props = @{}
$lp = Join-Path $repo 'local.properties'
if (Test-Path $lp) {
    Get-Content $lp | ForEach-Object {
        if ($_ -match '^\s*([A-Z_]+)\s*=\s*(.+)$') { $props[$Matches[1]] = $Matches[2].Trim() }
    }
}
$store = if ($props['DEV_STORE_FILE']) { $props['DEV_STORE_FILE'] } else { 'disad-dev.keystore' }
$ks = Join-Path $repo "app\$store"
if (Test-Path $ks) {
    $alias = if ($props['DEV_KEY_ALIAS']) { $props['DEV_KEY_ALIAS'] } else { 'baidukiller' }
    $sp = if ($props['DEV_STORE_PASSWORD']) { $props['DEV_STORE_PASSWORD'] } else { 'android' }
    $kp = if ($props['DEV_KEY_PASSWORD']) { $props['DEV_KEY_PASSWORD'] } else { 'android' }
    & (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-key-alias $alias `
        --ks-pass "pass:$sp" --key-pass "pass:$kp" --out $signed $aligned
    if ($LASTEXITCODE -ne 0) { throw "签名失败（退出码 $LASTEXITCODE）" }
} else {
    Write-Warning "没有找到 $ks，跳过签名（产出仍是未签名包）：$aligned"
    $signed = $aligned
}

Write-Host "== 4/4 校验 ==" -ForegroundColor Cyan
& (Join-Path $bt 'zipalign.exe') -c -P 16 -v 4 $signed | Select-String -Pattern '\.so|BAD'
if ($LASTEXITCODE -ne 0) { Write-Warning "16KB 对齐校验未通过" } else { Write-Host "16KB 对齐：通过" -ForegroundColor Green }
if (Test-Path $ks) {
    & (Join-Path $bt 'apksigner.bat') verify --print-certs $signed | Select-String -Pattern 'SHA-256 digest'
}
& (Join-Path $bt 'aapt2.exe') dump badging $signed | Select-String -Pattern '^package:'
Write-Host "产物：$signed" -ForegroundColor Green
