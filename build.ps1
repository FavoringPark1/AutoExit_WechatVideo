# 安心守护 —— 无 Gradle 手动构建脚本（aapt2 + javac + d8 + zipalign + apksigner）
[CmdletBinding()]
param(
    [switch]$SkipSign
)

$ErrorActionPreference = 'Stop'

$root    = $PSScriptRoot
$app     = Join-Path $root 'app'
$bt      = Join-Path $root 'toolchain\build-tools\android-14'
$andjar  = Join-Path $root 'toolchain\platform\android-34-ext12\android.jar'
$libjars = Join-Path $root 'toolchain\libs\jars'
$build   = Join-Path $app 'build'
$dist    = Join-Path $root 'out'
$ksdir   = Join-Path $root 'toolchain\keystore'
$ks      = Join-Path $ksdir 'anxin.jks'
$kspass  = 'anxin2024'

$jdk     = 'C:\Program Files\Java\jdk-23'
$javac   = Join-Path $jdk 'bin\javac.exe'
$keytool = Join-Path $jdk 'bin\keytool.exe'

$env:JAVA_HOME = $jdk

$aapt2     = Join-Path $bt 'aapt2.exe'
$zipalign  = Join-Path $bt 'zipalign.exe'
$apksigner = Join-Path $bt 'apksigner.bat'

# build-tools 34 自带的 R8 8.2.2 解析不了 JDK 23 生成的匿名内部类
# （InnerClasses 属性中 outer_class_info_index 为 0），会 NPE，所以用 37 的 d8。
$d8        = Join-Path $root 'toolchain\build-tools37\android-37.0\d8.bat'

function Step($n) { Write-Host "`n=== $n ===" -ForegroundColor Cyan }
function Die($m)  { Write-Host "FAILED: $m" -ForegroundColor Red; exit 1 }

foreach ($p in @($aapt2, $d8, $zipalign, $apksigner, $andjar, $javac, $keytool)) {
    if (-not (Test-Path $p)) { Die "缺少工具: $p" }
}

# ---------------------------------------------------------------- 0. 清理
Step '0/8 清理构建目录'
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force -Path $build, $dist,
    (Join-Path $build 'classes'), (Join-Path $build 'gen'), (Join-Path $build 'dex') | Out-Null

# ---------------------------------------------------------------- 1. 图标
Step '1/8 生成图标'
$iconTmp = Join-Path $app 'res\mipmap-xxhdpi\ic_launcher.png'
if (Test-Path $iconTmp) { Remove-Item (Join-Path $app 'res\mipmap-*') -Recurse -Force -ErrorAction SilentlyContinue }
& python (Join-Path $root 'tools\make_icon.py') $app
if ($LASTEXITCODE -ne 0) { Die '图标生成失败' }

# ---------------------------------------------------------------- 2. 资源编译
Step '2/8 aapt2 compile'
$resZip = Join-Path $build 'res.zip'
& $aapt2 compile --dir (Join-Path $app 'res') -o $resZip
if ($LASTEXITCODE -ne 0) { Die 'aapt2 compile 失败' }

# ---------------------------------------------------------------- 3. 资源链接
Step '3/8 aapt2 link'
$baseApk = Join-Path $build 'base.apk'
& $aapt2 link `
    -o $baseApk `
    -I $andjar `
    --manifest (Join-Path $app 'AndroidManifest.xml') `
    -R $resZip `
    --java (Join-Path $build 'gen') `
    --min-sdk-version 21 `
    --target-sdk-version 33 `
    --version-code 6 `
    --version-name 1.5 `
    --auto-add-overlay
if ($LASTEXITCODE -ne 0) { Die 'aapt2 link 失败' }

# ---------------------------------------------------------------- 4. javac
Step '4/8 javac'
$srcs = @(Get-ChildItem -Path (Join-Path $app 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$gen  = @(Get-ChildItem -Path (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host ("  源文件 {0} 个 + 生成 {1} 个" -f $srcs.Count, $gen.Count)

$cp = @($andjar)
if (Test-Path $libjars) {
    Get-ChildItem $libjars -Filter *.jar | ForEach-Object { $cp += $_.FullName }
}
$cpStr = $cp -join ';'

$classesDir = Join-Path $build 'classes'
& $javac -encoding UTF-8 -nowarn -Xlint:-options `
    -source 8 -target 8 `
    -bootclasspath $andjar `
    -cp $cpStr `
    -d $classesDir `
    ($srcs + $gen)
if ($LASTEXITCODE -ne 0) { Die 'javac 编译失败' }
Write-Host '  编译通过'

# ---------------------------------------------------------------- 5. d8
Step '5/8 d8 生成 dex'
$classFiles = @(Get-ChildItem -Path $classesDir -Recurse -Filter *.class | ForEach-Object { $_.FullName })
$jarFiles   = @()
if (Test-Path $libjars) {
    $jarFiles = @(Get-ChildItem $libjars -Filter *.jar | ForEach-Object { $_.FullName })
}
$dexDir = Join-Path $build 'dex'
& $d8 --release --min-api 21 --lib $andjar --output $dexDir ($classFiles + $jarFiles)
if ($LASTEXITCODE -ne 0) { Die 'd8 失败' }
if (-not (Test-Path (Join-Path $dexDir 'classes.dex'))) { Die 'd8 没有产出 classes.dex' }

# ---------------------------------------------------------------- 6. 打包 dex
Step '6/8 把 classes.dex 塞进 APK'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($baseApk, 'Update')
try {
    $existing = $zip.Entries | Where-Object { $_.FullName -eq 'classes.dex' }
    foreach ($e in $existing) { $zip.Entries.Remove($e) | Out-Null }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $dexDir 'classes.dex'), 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} finally {
    $zip.Dispose()
}

# ---------------------------------------------------------------- 7. zipalign
Step '7/8 zipalign'
$alignedApk = Join-Path $build 'aligned.apk'
& $zipalign -p -f 4 $baseApk $alignedApk
if ($LASTEXITCODE -ne 0) { Die 'zipalign 失败' }

if ($SkipSign) {
    Write-Host "`n未签名产物: $alignedApk" -ForegroundColor Yellow
    exit 0
}

# ---------------------------------------------------------------- 8. 签名
Step '8/8 签名'
New-Item -ItemType Directory -Force -Path $ksdir | Out-Null
if (-not (Test-Path $ks)) {
    Write-Host '  生成签名密钥…'
    & $keytool -genkeypair -keystore $ks -storepass $kspass -keypass $kspass `
        -alias anxin -keyalg RSA -keysize 2048 -validity 10950 `
        -dname "CN=AnXin, OU=Family, O=Home, L=City, ST=State, C=CN" 2>&1 | Out-Null
    if (-not (Test-Path $ks)) { Die 'keytool 生成密钥失败' }
}

$finalApk = Join-Path $dist 'tiaoguanggao-v1.5.apk'
if (Test-Path $finalApk) { Remove-Item $finalApk -Force }

& $apksigner sign `
    --ks $ks `
    --ks-key-alias anxin `
    --ks-pass "pass:$kspass" `
    --key-pass "pass:$kspass" `
    --min-sdk-version 21 `
    --v1-signing-enabled true `
    --v2-signing-enabled true `
    --out $finalApk `
    $alignedApk
if ($LASTEXITCODE -ne 0) { Die 'apksigner 签名失败' }

& $apksigner verify --verbose $finalApk
if ($LASTEXITCODE -ne 0) { Die 'apksigner 校验失败' }

$size = [math]::Round((Get-Item $finalApk).Length / 1KB, 1)
Write-Host "`n构建成功: $finalApk  ($size KB)" -ForegroundColor Green
