param(
    [string]$JdkRoot = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$signingRoot = Join-Path $androidRoot '.signing'
$keystore = Join-Path $signingRoot 'debug.keystore'

if (-not (Test-Path -LiteralPath $keystore)) {
    if (-not $JdkRoot) { $JdkRoot = 'C:\Program Files\Android\Android Studio\jbr' }
    $keytool = Join-Path $JdkRoot 'bin\keytool.exe'
    if (-not (Test-Path -LiteralPath $keytool)) { throw "Falta herramienta: $keytool" }
    New-Item -ItemType Directory -Force -Path $signingRoot | Out-Null
    & $keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la firma de desarrollo.' }
}

$gradle = Join-Path $androidRoot 'gradlew.bat'
Push-Location $androidRoot
try {
    & $gradle :app:assembleDebug --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilacion del APK.' }
    $builtApk = Join-Path $androidRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $outputApk = Join-Path $androidRoot 'GemeloDigitalBLE-debug.apk'
    Copy-Item -LiteralPath $builtApk -Destination $outputApk -Force
    Write-Host "APK listo: $outputApk"
} finally {
    Pop-Location
}
