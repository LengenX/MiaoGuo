# Manual APK build: aapt2 -> javac -> d8 -> pack dex -> zipalign -> apksigner
# No Gradle: this project does not need a whole build system for one APK.
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 reads .ps1 as ANSI
# unless there is a BOM, so non-ASCII text here breaks the parser.
#
# Two portability traps this script works around:
#  1) aapt2 on Windows cannot open source directories whose path has non-ASCII
#     characters, so everything is staged into an ASCII temp dir and the result
#     is copied back. The toolchain itself is linked by junction, not copied.
#  2) versionCode/versionName live only in AndroidManifest.xml; they are read
#     from there so the two never drift apart.
$ErrorActionPreference = 'Stop'

$root  = $PSScriptRoot
$appIn = Join-Path $root 'app'
$out   = Join-Path $root 'out'
$tcIn  = Join-Path $root 'toolchain'

function Step($msg) {
    Write-Host ""
    Write-Host ("=== " + $msg) -ForegroundColor Cyan
}

function Run($exe, $argList, $what) {
    & $exe @argList
    if ($LASTEXITCODE -ne 0) { throw ($what + " failed (exit " + $LASTEXITCODE + ")") }
}

# ---------- version comes from the manifest ----------
[xml]$mf = Get-Content (Join-Path $appIn 'AndroidManifest.xml')
$verName = $mf.manifest.versionName
$verCode = $mf.manifest.versionCode
if (-not $verName -or -not $verCode) { throw "versionCode/versionName missing in AndroidManifest.xml" }

# ---------- staging dir (must be pure ASCII for aapt2) ----------
$stage = Join-Path $env:TEMP 'miaoguo-build'
if ($stage -match '[^\x00-\x7F]') { $stage = 'C:\miaoguo-build' }
$stage = [System.IO.Path]::GetFullPath($stage)
# Remove an old junction with rmdir first: Remove-Item -Recurse would follow it
# and delete the real toolchain.
if (Test-Path (Join-Path $stage 'toolchain')) { cmd /c rmdir (Join-Path $stage 'toolchain') | Out-Null }
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

Write-Host ("staging: " + $stage)

Copy-Item -Path $appIn -Destination (Join-Path $stage 'app') -Recurse -Force
Copy-Item -Path (Join-Path $root 'pack_dex.py') -Destination $stage -Force
if (Test-Path (Join-Path $root 'keystore.jks')) {
    Copy-Item -Path (Join-Path $root 'keystore.jks') -Destination $stage -Force
}

$tc = Join-Path $stage 'toolchain'
if (Test-Path $tcIn) {
    New-Item -ItemType Junction -Path $tc -Target $tcIn | Out-Null
} else {
    New-Item -ItemType Directory -Force -Path $tc | Out-Null
}

$app    = Join-Path $stage 'app'
$build  = Join-Path $stage 'build'
$sdk    = Join-Path $tc 'sdk'
$bt     = Join-Path $sdk 'build-tools\34.0.0'
$pf     = Join-Path $sdk 'platforms\android-34'

# ---------- JDK: MIAOGUO_JDK overrides the bundled one ----------
$jdk = $env:MIAOGUO_JDK
if (-not $jdk -or -not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    $jdk = Join-Path $tc 'jdk'
}
$java       = Join-Path $jdk 'bin\java.exe'
$javac      = Join-Path $jdk 'bin\javac.exe'
$keytool    = Join-Path $jdk 'bin\keytool.exe'
$androidJar = Join-Path $pf 'android.jar'
$aapt2      = Join-Path $bt 'aapt2.exe'
$zipalign   = Join-Path $bt 'zipalign.exe'
$d8Jar      = Join-Path $bt 'lib\d8.jar'
$signerJar  = Join-Path $bt 'lib\apksigner.jar'

$ks      = Join-Path $stage 'keystore.jks'
$ksPass  = 'miaoguo123'
$ksAlias = 'miaoguo'

# ---------- find python (only used to patch classes.dex into the apk) ----------
$py = $env:MIAOGUO_PYTHON
if (-not $py -or -not (Test-Path $py)) {
    $candidate = Join-Path $env:USERPROFILE '.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe'
    if (Test-Path $candidate) { $py = $candidate }
}
if (-not $py -or -not (Test-Path $py)) {
    $cmd = Get-Command python.exe -ErrorAction SilentlyContinue
    if ($cmd) { $py = $cmd.Source }
}
if (-not $py -or -not (Test-Path $py)) {
    throw "python.exe not found. Set MIAOGUO_PYTHON or install python, needed by pack_dex.py."
}

# ---------- toolchain sanity ----------
if (-not (Test-Path $aapt2))     { throw ("toolchain missing: " + $aapt2 + " - run extract.py after downloading dl\*.zip") }
if (-not (Test-Path $androidJar)) { throw ("toolchain missing: " + $androidJar) }
if (-not (Test-Path $javac))     { throw ("jdk missing: " + $javac + " - set MIAOGUO_JDK or unpack toolchain\jdk") }

Step ("building " + $verName + " (versionCode " + $verCode + ")")

# ---------- 0. clean ----------
Step "0/8 clean"
New-Item -ItemType Directory -Force -Path $build, $out, (Join-Path $build 'gen'), (Join-Path $build 'classes'), (Join-Path $build 'dex') | Out-Null

# ---------- 1. compile resources ----------
Step "1/8 aapt2 compile"
$resZip = Join-Path $build 'res.zip'
Run $aapt2 @('compile', '--dir', (Join-Path $app 'res'), '-o', $resZip) 'aapt2 compile'

# ---------- 2. link resources, generate R.java ----------
Step "2/8 aapt2 link"
$baseApk = Join-Path $build 'base.apk'
Run $aapt2 @(
    'link', '-o', $baseApk,
    '-I', $androidJar,
    '--manifest', (Join-Path $app 'AndroidManifest.xml'),
    '-R', $resZip,
    '--java', (Join-Path $build 'gen'),
    '--min-sdk-version', '24',
    '--target-sdk-version', '34',
    '--auto-add-overlay'
) 'aapt2 link'

# ---------- 3. compile java ----------
Step "3/8 javac"
$sources  = @(Get-ChildItem -Path (Join-Path $app 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += @(Get-ChildItem -Path (Join-Path $build 'gen') -Recurse -Filter *.java | ForEach-Object { $_.FullName })
Write-Host ("sources: " + $sources.Count)
$javacArgs = @('-source', '11', '-target', '11', '-encoding', 'UTF-8', '-nowarn', '-cp', $androidJar, '-d', (Join-Path $build 'classes')) + $sources
& $javac @javacArgs
if ($LASTEXITCODE -ne 0) { throw ("javac failed (exit " + $LASTEXITCODE + ")") }

# ---------- 4. d8 ----------
Step "4/8 d8"
$classes = @(Get-ChildItem -Path (Join-Path $build 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName })
Write-Host ("class files: " + $classes.Count)
$d8Args = @('-Xmx1024m', '-cp', $d8Jar, 'com.android.tools.r8.D8', '--release', '--min-api', '24', '--lib', $androidJar, '--output', (Join-Path $build 'dex')) + $classes
Run $java $d8Args 'd8'

# ---------- 5. pack classes.dex into apk ----------
Step "5/8 pack dex"
$unaligned = Join-Path $build 'unaligned.apk'
& $py (Join-Path $stage 'pack_dex.py') $baseApk (Join-Path $build 'dex\classes.dex') $unaligned
if ($LASTEXITCODE -ne 0) { throw 'pack dex failed' }

# ---------- 6. zipalign ----------
Step "6/8 zipalign"
$aligned = Join-Path $build 'aligned.apk'
Run $zipalign @('-f', '-p', '4', $unaligned, $aligned) 'zipalign'

# ---------- 7. sign ----------
Step "7/8 apksigner"
if (-not (Test-Path $ks)) {
    Write-Host "generating self-signed keystore (personal use, 10 years)"
    & $keytool @(
        '-genkeypair', '-keystore', $ks, '-alias', $ksAlias,
        '-keyalg', 'RSA', '-keysize', '2048', '-validity', '3650',
        '-storepass', $ksPass, '-keypass', $ksPass,
        '-dname', 'CN=MiaoGuo, OU=Personal, O=Personal, L=Shenzhen, ST=Guangdong, C=CN'
    )
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}
New-Item -ItemType Directory -Force -Path $out | Out-Null
$final = Join-Path $out ("miaoguo-" + $verName + ".apk")
Run $java @('-jar', $signerJar, 'sign', '--ks', $ks, '--ks-pass', ("pass:" + $ksPass), '--key-pass', ("pass:" + $ksPass), '--min-sdk-version', '24', '--out', $final, $aligned) 'apksigner sign'

# ---------- 8. verify ----------
Step "8/8 apksigner verify"
& $java @('-jar', $signerJar, 'verify', '--print-certs', $final)
if ($LASTEXITCODE -ne 0) { throw 'apksigner verify failed' }

$fi = Get-Item $final
Write-Host ""
Write-Host ("BUILD_OK  " + $fi.FullName + "  " + [math]::Round($fi.Length / 1KB, 1) + " KB") -ForegroundColor Green
