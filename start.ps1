# Kinoko 服务端一键启动脚本
# 用法：
#   .\start.ps1                  默认启动（5 频道、调试模式、自动建账号）
#   .\start.ps1 -Channels 2      仅启动 2 个频道
#   .\start.ps1 -Debug $false    关闭调试日志
#   .\start.ps1 -Build           重新构建后启动
#   .\start.ps1 -NoLog           不写日志文件（仅控制台输出）
# 日志默认写入 logs\server_yyyyMMdd_HHmmss.log（按启动时间分文件）
# 按 Ctrl+C 停止服务端

param(
    [int]$Channels = 5,
    [bool]$Debug = $true,
    [bool]$AutoCreateAccount = $true,
    [string]$WorldName = "Kinoko",
    [switch]$Build,
    [switch]$NoLog
)

# ===== 路径常量 =====
$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$JdkHome    = Join-Path $ScriptDir "tools\jdk21.0.12_8"
$MavenHome  = Join-Path $ScriptDir "tools\apache-maven-3.9.9"
$JarPath    = Join-Path $ScriptDir "target\server.jar"
$WzDir      = Join-Path $ScriptDir "wz"
$DataDir    = Join-Path $ScriptDir "data"
$LogDir     = Join-Path $ScriptDir "logs"

# ===== 必需 WZ 文件 =====
$RequiredWz = @(
    "Character.wz","Item.wz","Skill.wz","Morph.wz","Map.wz",
    "Mob.wz","Npc.wz","Reactor.wz","Quest.wz","String.wz","Etc.wz"
)

# ===== 颜色输出 =====
function Write-Step($m) { Write-Host "[STEP] $m" -ForegroundColor Cyan }
function Write-Ok($m)   { Write-Host "[OK]   $m" -ForegroundColor Green }
function Write-Warn($m) { Write-Host "[WARN] $m" -ForegroundColor Yellow }
function Write-Err($m)  { Write-Host "[ERR]  $m" -ForegroundColor Red }

# ===== 1. 检查 JDK =====
Write-Step "Checking JDK..."
$JavaExe = Join-Path $JdkHome "bin\java.exe"
if (-not (Test-Path $JavaExe)) {
    Write-Err "JDK not found: $JdkHome"
    Write-Err "Please download Amazon Corretto 21 zip and extract to tools\jdk21.0.12_8"
    exit 1
}
$env:JAVA_HOME = $JdkHome
$env:PATH = "$JdkHome\bin;$env:PATH"
$javaVer = (& $JavaExe -version 2>&1 | Select-Object -First 1)
Write-Ok "JDK: $javaVer"

# ===== 2. 检查 WZ 文件 =====
Write-Step "Checking WZ files..."
$missing = @()
foreach ($wz in $RequiredWz) {
    if (-not (Test-Path (Join-Path $WzDir $wz))) { $missing += $wz }
}
if ($missing.Count -gt 0) {
    Write-Err "Missing WZ files: $($missing -join ', ')"
    Write-Err "Please put them in: $WzDir"
    exit 1
}
Write-Ok "All $($RequiredWz.Count) WZ files present"

# ===== 3. 检查端口占用 =====
Write-Step "Checking ports..."
$PortsToCheck = @(8282, 8484)
$PortsToCheck += 8585..(8584 + $Channels)
$busy = @()
foreach ($port in $PortsToCheck) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) { $busy += $port }
}
if ($busy.Count -gt 0) {
    Write-Err "Ports already in use: $($busy -join ', ')"
    Write-Err "Stop existing server:"
    Write-Err "  Get-NetTCPConnection -LocalPort $($busy[0]) -State Listen | ForEach-Object { Stop-Process -Id `$_.OwningProcess -Force }"
    exit 1
}
Write-Ok "All ports free: $($PortsToCheck -join ', ')"

# ===== 4. 构建或检查 jar =====
$RequiredJarEntries = @(
    'kinoko/server/migration/MigrationInfo.class',
    'kinoko/server/migration/MigrationInfo$TemporaryStatOptionType.class'
)
$NeedBuild = $Build -or -not (Test-Path $JarPath)

if (-not $NeedBuild) {
    $jarItem = Get-Item $JarPath
    $sourceRoot = Join-Path $ScriptDir "src\main"
    $newerSource = Get-ChildItem $sourceRoot -Recurse -File -Filter *.java |
        Where-Object { $_.LastWriteTimeUtc -gt $jarItem.LastWriteTimeUtc } |
        Select-Object -First 1
    if ($newerSource) {
        $NeedBuild = $true
        Write-Warn "Source files are newer than server.jar"
    }
}

if (-not $NeedBuild) {
    $jarExe = Join-Path $JdkHome "bin\jar.exe"
    $jarEntries = & $jarExe tf $JarPath
    if ($LASTEXITCODE -ne 0 -or @($RequiredJarEntries | Where-Object { $jarEntries -notcontains $_ }).Count -gt 0) {
        $NeedBuild = $true
        Write-Warn "server.jar is missing required migration classes"
    }
}

if ($NeedBuild) {
    if (-not (Test-Path $MavenHome)) {
        Write-Err "Maven not found: $MavenHome"
        exit 1
    }
    Write-Step "Building server.jar (mvn clean package)..."
    $env:MAVEN_HOME = $MavenHome
    $env:PATH = "$MavenHome\bin;$env:PATH"
    & "$MavenHome\bin\mvn.cmd" clean package "-Dmaven.test.skip=true" -B
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Build failed"
        exit 1
    }
    Write-Ok "Build success"
}

$jarExe = Join-Path $JdkHome "bin\jar.exe"
$jarEntries = & $jarExe tf $JarPath
if ($LASTEXITCODE -ne 0 -or @($RequiredJarEntries | Where-Object { $jarEntries -notcontains $_ }).Count -gt 0) {
    Write-Err "server.jar is incomplete: migration classes are missing"
    exit 1
}
$jarSize = [math]::Round((Get-Item $JarPath).Length / 1MB, 1)
Write-Ok "server.jar verified (${jarSize}MB)"

# ===== 5. 设置环境变量 =====
$env:WORLD_NAME           = $WorldName
$env:CHANNEL_COUNT        = $Channels
$env:DEBUG_MODE           = $Debug.ToString().ToLower()
$env:AUTO_CREATE_ACCOUNT  = $AutoCreateAccount.ToString().ToLower()
$env:WZ_DIRECTORY         = $WzDir
$env:DATA_DIRECTORY       = $DataDir

# ===== 6. 打印启动信息 =====
Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Kinoko Server Starting" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  World         : $WorldName"
Write-Host "  Channels      : $Channels (ports 8585..$(8584 + $Channels))"
Write-Host "  Login port    : 8484  (client first connect)"
Write-Host "  Central port  : 8282  (internal)"
Write-Host "  Debug         : $Debug"
Write-Host "  Auto account  : $AutoCreateAccount"
Write-Host "  WZ dir        : $WzDir"
Write-Host "  Data dir      : $DataDir"
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host "  Press Ctrl+C to stop the server" -ForegroundColor DarkGray
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host ""

# ===== 7. 启动服务端 =====
Set-Location $ScriptDir
if ($NoLog) {
    & $JavaExe -jar $JarPath
} else {
    # 日志按启动时间分文件存入 logs\ 目录，避免单一 server.log 无限增长。
    # 每次启动生成 server_yyyyMMdd_HHmmss.log（例如 server_20260808_213000.log）。
    if (-not (Test-Path $LogDir)) {
        New-Item -ItemType Directory -Path $LogDir -Force | Out-Null
    }
    $LogFile = Join-Path $LogDir ("server_{0}.log" -f (Get-Date -Format "yyyyMMdd_HHmmss"))
    Write-Warn "Logging to: $LogFile"
    Write-Host ""
    & $JavaExe -jar $JarPath 2>&1 | Tee-Object -FilePath $LogFile
}
