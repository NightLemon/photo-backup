[CmdletBinding()]
param(
    [string]$Executable
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($Executable)) {
    $serverDir = Split-Path -Parent $PSScriptRoot
    $Executable = Join-Path $serverDir "photobackup-server.exe"
}

$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw "Run this script as Administrator."
}

$resolvedExecutable = (Resolve-Path -LiteralPath $Executable).Path
$apiRule = "Home Photo Backup Standalone API"
$mdnsRule = "Home Photo Backup Standalone mDNS"

Get-NetFirewallRule -DisplayName $apiRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Get-NetFirewallRule -DisplayName $mdnsRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule

New-NetFirewallRule -DisplayName $apiRule -Direction Inbound -Action Allow -Profile Private -Protocol TCP -LocalPort 5443 -Program $resolvedExecutable | Out-Null
New-NetFirewallRule -DisplayName $mdnsRule -Direction Inbound -Action Allow -Profile Private -Protocol UDP -LocalPort 5353 -Program $resolvedExecutable | Out-Null

Write-Host "Standalone firewall rules enabled for Private networks."
Write-Host "Executable: $resolvedExecutable"