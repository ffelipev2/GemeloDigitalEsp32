param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JdkRoot = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'
$androidRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$repoRoot = [IO.Path]::GetFullPath((Join-Path $androidRoot '..'))
$buildRoot = [IO.Path]::GetFullPath((Join-Path $androidRoot '.build'))
$signingRoot = [IO.Path]::GetFullPath((Join-Path $androidRoot '.signing'))

if (-not $buildRoot.StartsWith($androidRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'La carpeta temporal debe estar dentro de android.'
}
if (-not $signingRoot.StartsWith($androidRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'La firma debe estar dentro de android.'
}

if (-not $SdkRoot) { $SdkRoot = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not $JdkRoot) { $JdkRoot = 'C:\Program Files\Android\Android Studio\jbr' }
$toolsRoot = Join-Path $SdkRoot 'build-tools\34.0.0'
$platformJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$aapt2 = Join-Path $toolsRoot 'aapt2.exe'
$d8 = Join-Path $SdkRoot 'build-tools\36.0.0\d8.bat'
$zipalign = Join-Path $toolsRoot 'zipalign.exe'
$apksigner = Join-Path $toolsRoot 'apksigner.bat'
$javac = Join-Path $JdkRoot 'bin\javac.exe'
$jar = Join-Path $JdkRoot 'bin\jar.exe'
$keytool = Join-Path $JdkRoot 'bin\keytool.exe'

foreach ($path in @($platformJar, $aapt2, $d8, $zipalign, $apksigner, $javac, $jar, $keytool)) {
    if (-not (Test-Path -LiteralPath $path)) { throw "Falta herramienta: $path" }
}

if (Test-Path -LiteralPath $buildRoot) {
    Remove-Item -LiteralPath $buildRoot -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $buildRoot, (Join-Path $buildRoot 'classes'), (Join-Path $buildRoot 'dex'), $signingRoot | Out-Null
$env:JAVA_HOME = $JdkRoot
$androidJar = Join-Path $buildRoot 'android.jar'
Copy-Item -LiteralPath $platformJar -Destination $androidJar

$manifest = Join-Path $androidRoot 'app\src\main\AndroidManifest.xml'
$resources = Join-Path $androidRoot 'app\src\main\res'
$javaSource = Join-Path $androidRoot 'app\src\main\java\com\gemelodigital\esp32\MainActivity.java'
$assets = Join-Path $repoRoot 'data'
$compiledResources = Join-Path $buildRoot 'resources.zip'
$unsignedApk = Join-Path $buildRoot 'unsigned.apk'
$alignedApk = Join-Path $buildRoot 'aligned.apk'
$classesDir = Join-Path $buildRoot 'classes'
$dexDir = Join-Path $buildRoot 'dex'
$classesJar = Join-Path $buildRoot 'classes.jar'
$outputApk = Join-Path $androidRoot 'GemeloDigitalBLE-debug.apk'
$keystore = Join-Path $signingRoot 'debug.keystore'

& $aapt2 compile --dir $resources -o $compiledResources
if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilación de recursos.' }

& $aapt2 link -o $unsignedApk --manifest $manifest -I $androidJar -A $assets -R $compiledResources --auto-add-overlay --min-sdk-version 23 --target-sdk-version 34 --version-code 1 --version-name 1.0 --debug-mode
if ($LASTEXITCODE -ne 0) { throw 'Fallo el enlace del APK.' }

& $javac --release 8 -classpath $androidJar -d $classesDir $javaSource
if ($LASTEXITCODE -ne 0) { throw 'Fallo la compilación Java.' }

& $jar cf $classesJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear el archivo de clases.' }
& $d8 --lib $androidJar --min-api 23 --output $dexDir $classesJar
if ($LASTEXITCODE -ne 0) { throw 'Fallo la conversión a DEX.' }

& $jar uf $unsignedApk -C $dexDir classes.dex
if ($LASTEXITCODE -ne 0) { throw 'No se pudo añadir classes.dex.' }
& $zipalign -f -p 4 $unsignedApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'Fallo zipalign.' }

if (-not (Test-Path -LiteralPath $keystore)) {
    & $keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo crear la firma de desarrollo.' }
}

& $apksigner sign --ks $keystore --ks-pass pass:android --key-pass pass:android --out $outputApk $alignedApk
if ($LASTEXITCODE -ne 0) { throw 'Fallo la firma del APK.' }
& $apksigner verify --verbose $outputApk
if ($LASTEXITCODE -ne 0) { throw 'La firma del APK no es válida.' }

Write-Host "APK listo: $outputApk"
