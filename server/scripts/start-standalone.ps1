[CmdletBinding()]
param(
    [string]$StorageRoot,
    [string]$StateDir = (Join-Path $env:LOCALAPPDATA "HomePhotoBackupStandalone"),
    [string]$ServerName = "Home Photo Backup",
    [switch]$NoBrowser
)

$ErrorActionPreference = "Stop"
$serverDir = Split-Path -Parent $PSScriptRoot
$executable = Join-Path $serverDir "photobackup-server.exe"
$configPath = Join-Path $StateDir "config.json"

if (-not (Test-Path -LiteralPath $executable -PathType Leaf)) {
    throw "Server executable not found: $executable"
}

if ([string]::IsNullOrWhiteSpace($StorageRoot) -and -not (Test-Path -LiteralPath $configPath -PathType Leaf)) {
    $pictures = [Environment]::GetFolderPath("MyPictures")
    if ([string]::IsNullOrWhiteSpace($pictures)) {
        $pictures = Join-Path $env:USERPROFILE "Pictures"
    }
    $StorageRoot = Join-Path $pictures "HomePhotoBackup"
}

$initArguments = @("init", "--state-dir", $StateDir, "--name", $ServerName)
if (-not [string]::IsNullOrWhiteSpace($StorageRoot)) {
    $initArguments += @("--storage-root", $StorageRoot)
}

& $executable @initArguments
if ($LASTEXITCODE -ne 0) {
    throw "Server initialization failed."
}

$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
Write-Host "State directory: $($config.stateDir)"
Write-Host "Photo directory: $($config.storageRoot)"
Write-Host "Keep this window open. Press Ctrl+C to stop the server."
Write-Host "Phone access requires Windows Firewall permission on Private networks."

$quotedStateDir = '"' + $StateDir.Replace('"', '\"') + '"'
$process = Start-Process -FilePath $executable -ArgumentList @(
    "serve",
    "--state-dir",
    $quotedStateDir
) -PassThru -NoNewWindow

try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        if ($process.HasExited) {
            break
        }
        try {
            $response = Invoke-WebRequest -Uri "http://127.0.0.1:5444/admin/status" -UseBasicParsing -TimeoutSec 1
            if ($response.StatusCode -eq 200) {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 100
        }
    }

    if (-not $ready) {
        if ($process.HasExited) {
            throw "Server exited before the dashboard became ready. Exit code: $($process.ExitCode)"
        }
        throw "Dashboard did not become ready at http://127.0.0.1:5444"
    }

    if (-not $NoBrowser) {
        Start-Process "http://127.0.0.1:5444"
    }
    Write-Host "Dashboard: http://127.0.0.1:5444"

    Wait-Process -Id $process.Id
    $process.Refresh()
    if ($process.ExitCode -ne 0) {
        throw "Server exited with code $($process.ExitCode)."
    }
} finally {
    if ($null -ne $process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
    }
}