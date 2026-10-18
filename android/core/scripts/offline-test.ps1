<#
    Offline build + test for the `core` module on Windows.

    The checked-in `scripts/test-core.py` expects a Linux toolchain layout
    (`~/.local/share/ready-gym-tools` with JDK 17, Gradle 8.9, and pre-downloaded JUnit jars).
    This script does the same compile-and-run with only what ships on a normal Windows dev box:

      * a JDK 21-25 (override with -JavaHome). Kotlin 1.9/2.x compilers cannot parse a JDK 26
        version string, so a JDK 26 will fail with `IllegalArgumentException: 26.0.x`.
      * the standalone `kotlinc` bundled with Android Studio (or any IDEA install), which already
        carries its own stdlib and is version-matched to itself.
      * a JUnit 4 jar. The one vendored inside PyCharm's debugpy helpers bundles Hamcrest with it.

    Nothing here touches the network or Gradle caches. It is a developer convenience; CI still
    uses `test-core.py`.
#>
[CmdletBinding()]
param(
    [string]$JavaHome,
    [string]$KotlincHome,
    [string]$JUnitJar
)

$ErrorActionPreference = 'Stop'
$core = Split-Path -Parent $PSScriptRoot
$buildRoot = Join-Path $core 'build/offline-test'
$mainOut = Join-Path $buildRoot 'main'
$testOut = Join-Path $buildRoot 'test'

function Get-JavaMajor([string]$JavaExe) {
    # `java -version` writes to stderr; under $ErrorActionPreference='Stop' PowerShell would
    # surface that as a terminating NativeCommandError, so read it via a plain redirect.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $line = (& $JavaExe -version 2>&1 | Select-Object -First 1) -join ''
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($line -match '"(\d+)') { return [int]$Matches[1] }
    return 0
}

function Find-JavaExe([string]$JdkHome) {
    $candidates = @()
    if ($JdkHome) { $candidates += (Join-Path $JdkHome 'bin/java.exe') }
    if ($env:JAVA_HOME) { $candidates += (Join-Path $env:JAVA_HOME 'bin/java.exe') }
    $candidates += @(
        'D:\Program Files\Android\Android Studio\jbr\bin\java.exe',
        'C:\Program Files\Android\Android Studio\jbr\bin\java.exe',
        'D:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\jbr\bin\java.exe',
        'D:\PyCharm\jbr\bin\java.exe'
    )
    $rejected = @()
    foreach ($path in $candidates) {
        if (-not (Test-Path $path)) { continue }
        $major = Get-JavaMajor $path
        # The Kotlin 1.9/2.x compiler cannot parse a JDK 26 version string.
        if ($major -ge 26) { $rejected += "$path (JDK $major)"; continue }
        return $path
    }
    if ($JdkHome) { throw "No usable java.exe under -JavaHome '$JdkHome'" }
    $cmd = Get-Command java -ErrorAction SilentlyContinue
    if ($cmd -and (Get-JavaMajor $cmd.Source) -lt 26) { return $cmd.Source }
    $detail = if ($rejected) { " Rejected: " + ($rejected -join '; ') } else { '' }
    throw "No JDK 21-25 found (the bundled Kotlin compiler rejects JDK 26+).$detail Pass -JavaHome."
}

function Find-Kotlinc([string]$KotlinHome) {
    $candidates = @()
    if ($KotlinHome) { $candidates += $KotlinHome }
    $candidates += @(
        'D:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc',
        'C:\Program Files\Android\Android Studio\plugins\Kotlin\kotlinc',
        'D:\Program Files\JetBrains\IntelliJ IDEA 2026.2.0.1\plugins\Kotlin\kotlinc'
    )
    foreach ($path in $candidates) {
        if (Test-Path (Join-Path $path 'lib/kotlin-compiler.jar')) { return $path }
    }
    throw 'No bundled kotlinc found. Pass -KotlincHome.'
}

function Find-JUnit([string]$Jar) {
    if ($Jar) {
        if (Test-Path $Jar) { return $Jar }
        throw "No JUnit jar at '$Jar'"
    }
    $candidates = @(
        'D:\PyCharm\plugins\python-ce\helpers\debugpy\_vendored\pydevd\jython_test_deps\junit.jar',
        'C:\Program Files\JetBrains\PyCharm 2026.2.0.1\plugins\python-ce\helpers\debugpy\_vendored\pydevd\jython_test_deps\junit.jar'
    )
    foreach ($path in $candidates) { if (Test-Path $path) { return $path } }
    $cache = Join-Path $env:USERPROFILE '.gradle/caches/modules-2/files-2.1'
    if (Test-Path $cache) {
        $found = Get-ChildItem $cache -Recurse -Filter 'junit-4*.jar' -ErrorAction SilentlyContinue |
            Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    throw 'No JUnit 4 jar found. Pass -JUnitJar.'
}

$java = Find-JavaExe $JavaHome
$kotlinc = Find-Kotlinc $KotlincHome
$junit = Find-JUnit $JUnitJar
$stdlib = Join-Path $kotlinc 'lib/kotlin-stdlib.jar'

Write-Host "java    : $java"
Write-Host "kotlinc : $kotlinc"
Write-Host "junit   : $junit"

if (Test-Path $buildRoot) { Remove-Item $buildRoot -Recurse -Force }
New-Item -ItemType Directory -Force -Path $mainOut, $testOut | Out-Null

$compilerCp = Join-Path $kotlinc 'lib/*'

function Invoke-Kotlinc([string]$SourceDir, [string]$Destination, [string]$Classpath) {
    $arguments = @(
        '-cp', $compilerCp,
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-jvm-target', '17', '-classpath', $Classpath, '-d', $Destination, $SourceDir
    )
    # The compiler prints JDK deprecation warnings on stderr, which PowerShell treats as an
    # error record under $ErrorActionPreference='Stop'. Capture them as plain text instead.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & $java @arguments 2>&1
        $exit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $output |
        Where-Object { $_ -notmatch 'WARNING: (A terminally|sun\.misc|Please consider)' } |
        ForEach-Object { Write-Host $_ }
    if ($exit -ne 0) { throw "kotlinc failed for $SourceDir (exit $exit)" }
}

Write-Host "`n=== compiling main ===" -ForegroundColor Cyan
Invoke-Kotlinc -SourceDir (Join-Path $core 'src/main/kotlin') -Destination $mainOut -Classpath $stdlib

Write-Host "`n=== compiling tests ===" -ForegroundColor Cyan
Invoke-Kotlinc -SourceDir (Join-Path $core 'src/test/kotlin') `
    -Destination $testOut -Classpath "$mainOut;$junit;$stdlib"

$testClasses = Get-ChildItem $testOut -Recurse -Filter '*Test.class' |
    ForEach-Object { $_.FullName.Substring($testOut.Length + 1) -replace '\\', '.' -replace '\.class$', '' } |
    Sort-Object

Write-Host "`n=== running $($testClasses.Count) test classes ===" -ForegroundColor Cyan
$runCp = "$mainOut;$testOut;$junit;$stdlib"
$previous = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    & $java '-cp' $runCp 'org.junit.runner.JUnitCore' @testClasses
    $exit = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previous
}
exit $exit
