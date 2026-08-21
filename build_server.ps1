# Kinoko server build script
# Usage:
#   .\build_server.ps1              compile + package into server.jar
#   .\build_server.ps1 -Compile     compile .class only (faster)
#   .\build_server.ps1 -Clean       clean before build
#   .\build_server.ps1 -SkipTest    skip tests (default)
#   .\build_server.ps1 -RunTest     run tests
#
# Output: target\server.jar (package mode)
# Dependencies: tools\jdk21.0.12_8, tools\apache-maven-3.9.9

param(
    [switch]$Compile,
    [switch]$Clean,
    [switch]$SkipTest,
    [switch]$RunTest
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$JdkHome   = Join-Path $ScriptDir "tools\jdk21.0.12_8"
$MavenHome = Join-Path $ScriptDir "tools\apache-maven-3.9.9"

function Write-Step($m) { Write-Host "[STEP] $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "[OK]   $m" -ForegroundColor Green }
function Write-Err($m)  { Write-Host "[ERR]  $m" -ForegroundColor Red }

# check JDK
Write-Step "Checking JDK..."
$JavaExe = Join-Path $JdkHome "bin\java.exe"
if (-not (Test-Path $JavaExe)) {
    Write-Err "JDK not found: $JdkHome"
    exit 1
}
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
$javaVer = (& $JavaExe -version 2>&1 | Select-Object -First 1)
Write-Ok "JDK: $javaVer"

# check Maven
Write-Step "Checking Maven..."
$MvnCmd = Join-Path $MavenHome "bin\mvn.cmd"
if (-not (Test-Path $MvnCmd)) {
    Write-Err "Maven not found: $MavenHome"
    exit 1
}
$env:MAVEN_HOME = $MavenHome
$env:PATH = "$MavenHome\bin;$env:PATH"
$mvnVer = (& $MvnCmd --version 2>&1 | Select-Object -First 1)
Write-Ok "Maven: $mvnVer"

# build args
$goal = if ($Compile) { "compile" } else { "package" }
if ($Clean) { $goal = "clean $goal" }

$testFlag = ""
if ($RunTest) {
    Write-Step "Tests enabled (use -SkipTest to skip)"
} elseif (-not $SkipTest) {
    $testFlag = "-Dmaven.test.skip=true"
    Write-Step "Tests skipped (use -RunTest to enable)"
}

# execute build
Write-Step "Running: mvn $goal $testFlag"
Set-Location $ScriptDir
$stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
& $MvnCmd $goal.Split(' ') $testFlag.Split(' ') -B
$stopwatch.Stop()

if ($LASTEXITCODE -ne 0) {
    Write-Err "Build failed (exit code: $LASTEXITCODE)"
    exit 1
}

# result
if ($Compile) {
    Write-Ok "Compilation complete ($($stopwatch.Elapsed.TotalSeconds.ToString('F1'))s)"
} else {
    $jarPath = Join-Path $ScriptDir "target\server.jar"
    if (Test-Path $jarPath) {
        $jarSize = [math]::Round((Get-Item $jarPath).Length / 1MB, 1)
        Write-Ok "Build complete ($($stopwatch.Elapsed.TotalSeconds.ToString('F1'))s) - server.jar: ${jarSize}MB"
    } else {
        Write-Err "server.jar not found at $jarPath"
        exit 1
    }
}