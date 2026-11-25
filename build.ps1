# Compila l'APK di debug su Windows senza Gradle (kotlinc + aapt + d8 + zipalign + apksigner).
# Equivalente di build.sh; usa d8 al posto di dx (rimosso dagli Android build-tools recenti).
#
# Strumenti richiesti (percorsi sovrascrivibili con variabili d'ambiente):
#   JAVA_HOME     JDK 17                    (es. C:\Program Files\Microsoft\jdk-17...)
#   ANDROID_HOME  Android SDK con platforms\android-34 e build-tools\37.0.0
#   KOTLIN_HOME   compilatore Kotlin        (predefinito: %LOCALAPPDATA%\Programs\kotlinc)
#
# Gli strumenti .bat dell'SDK non sono usati: il percorso del progetto contiene "&",
# che cmd.exe interpreta come separatore di comandi. Si chiamano direttamente i .jar.
#
# Uso:  powershell -ExecutionPolicy Bypass -File .\build.ps1

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

$JavaHome    = if ($env:JAVA_HOME)    { $env:JAVA_HOME }    else { throw 'JAVA_HOME non impostata (serve un JDK 17)' }
$AndroidHome = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$KotlinHome  = if ($env:KOTLIN_HOME)  { $env:KOTLIN_HOME }  else { Join-Path $env:LOCALAPPDATA 'Programs\kotlinc' }
# build-tools recenti: il d8 di versioni vecchie (es. 34) non riconosce i metadati di Kotlin 2.4
$BuildTools  = if ($env:BUILD_TOOLS)  { $env:BUILD_TOOLS }  else { '37.0.0' }

$Java       = Join-Path $JavaHome 'bin\java.exe'
$Jar        = Join-Path $JavaHome 'bin\jar.exe'
$AndroidJar = Join-Path $AndroidHome 'platforms\android-34\android.jar'
$BT         = Join-Path $AndroidHome "build-tools\$BuildTools"
$Aapt       = Join-Path $BT 'aapt.exe'
$Zipalign   = Join-Path $BT 'zipalign.exe'
$D8Jar      = Join-Path $BT 'lib\d8.jar'
$SignerJar  = Join-Path $BT 'lib\apksigner.jar'
$KotlinJar  = Join-Path $KotlinHome 'lib\kotlin-compiler.jar'
$Stdlib     = Join-Path $KotlinHome 'lib\kotlin-stdlib.jar'

foreach ($f in $Java, $Jar, $AndroidJar, $Aapt, $Zipalign, $D8Jar, $SignerJar, $KotlinJar, $Stdlib) {
    if (-not (Test-Path -LiteralPath $f)) { throw "Strumento mancante: $f" }
}

function Invoke-Tool {
    param([string]$Exe, [string[]]$ToolArgs)
    & $Exe @ToolArgs
    if ($LASTEXITCODE -ne 0) { throw "Comando fallito ($LASTEXITCODE): $Exe" }
}

$Src = 'app\src\main'
$Out = 'build'
$Apk = 'CensimentoMezzi-debug.apk'
$Keystore = 'debug.keystore'
if (-not (Test-Path -LiteralPath $Keystore)) {
    throw "debug.keystore non trovato: senza la chiave originale l'aggiornamento non si installa sopra la versione presente sui telefoni"
}

if (Test-Path -LiteralPath $Out) { Remove-Item -LiteralPath $Out -Recurse -Force }
New-Item -ItemType Directory -Force "$Out\classes", "$Out\dex" | Out-Null

Write-Host '1/5 Risorse (aapt)'
Invoke-Tool $Aapt @('package', '-f', '-0', 'arsc', '-M', "$Src\AndroidManifest.xml", '-S', "$Src\res",
    '-I', $AndroidJar, '-F', "$Out\app.unaligned.apk")

Write-Host '2/5 Compilazione Kotlin'
$sources = Get-ChildItem -LiteralPath "$Src\java" -Recurse -Filter *.kt | ForEach-Object FullName
Invoke-Tool $Java (@('-cp', $KotlinJar, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-kotlin-home', $KotlinHome, '-no-reflect', '-cp', $AndroidJar, '-jvm-target', '1.8',
    '-Xlambdas=class', '-Xsam-conversions=class', '-Xstring-concat=inline',
    '-Xno-param-assertions', '-Xno-call-assertions', '-nowarn',
    '-d', "$Out\classes") + $sources)
if (-not (Test-Path -LiteralPath "$Out\classes\it\impresasangalli\censimentomezzi\MainActivity.class")) {
    throw 'Compilazione Kotlin non riuscita'
}

Write-Host '3/5 DEX (d8)'
Invoke-Tool $Jar @('cf', "$Out\classes.jar", '-C', "$Out\classes", '.')
Invoke-Tool $Java @('-cp', $D8Jar, 'com.android.tools.r8.D8', '--min-api', '26',
    '--lib', $AndroidJar, '--output', "$Out\dex", "$Out\classes.jar", $Stdlib)
Push-Location -LiteralPath "$Out\dex"
try { Invoke-Tool $Aapt @('add', '..\app.unaligned.apk', 'classes.dex') | Out-Null } finally { Pop-Location }

Write-Host '4/5 Allineamento'
Invoke-Tool $Zipalign @('-p', '-f', '4', "$Out\app.unaligned.apk", "$Out\app.aligned.apk")

Write-Host '5/5 Firma debug'
Invoke-Tool $Java @('-jar', $SignerJar, 'sign', '--ks', $Keystore, '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--ks-key-alias', 'androiddebugkey',
    '--v1-signing-enabled', 'true', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true',
    '--out', $Apk, "$Out\app.aligned.apk")
Invoke-Tool $Java @('-jar', $SignerJar, 'verify', '--verbose', '--print-certs', $Apk)
Get-Item -LiteralPath $Apk | Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize
