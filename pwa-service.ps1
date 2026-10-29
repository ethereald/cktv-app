param(
    [Parameter(Position = 0)]
    [ValidateSet('start', 'stop', 'restart', 'status')]
    [string]$Action = 'status'
)

$pwa = Join-Path $PSScriptRoot 'pwa'
$port = if ($env:PORT) { [int]$env:PORT } else { 8080 }
$httpsPort = if ($env:HTTPS_PORT) { [int]$env:HTTPS_PORT } else { 8443 }
$pidFile = Join-Path $pwa '.server.pid'

function Get-ServerPids {
    $ids = @(Get-NetTCPConnection -LocalPort $port, $httpsPort -State Listen -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique)
    $ids
}

function Show-Status {
    $ids = Get-ServerPids
    if ($ids.Count -eq 0) { Write-Host 'PWA service is stopped.'; return }
    Write-Host "PWA service is running (PID $($ids -join ', '))."
    Write-Host "  http://localhost:$port"
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -notmatch '^(127|169\.254)\.' } |
        ForEach-Object { Write-Host "  https://$($_.IPAddress):$httpsPort" }
}

function Stop-Server {
    $ids = Get-ServerPids
    if ($ids.Count -eq 0) { Write-Host 'PWA service is not running.'; return }
    $ids | ForEach-Object { Stop-Process -Id $_ -Force -ErrorAction SilentlyContinue }
    Remove-Item $pidFile -ErrorAction SilentlyContinue
    Write-Host 'PWA service stopped.'
}

function Start-Server {
    if ((Get-ServerPids).Count -gt 0) { Show-Status; return }
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js is required but was not found on PATH.' }
    if (-not (Test-Path (Join-Path $pwa 'certs\cert.pem'))) {
        Write-Host 'No HTTPS certificate found; generating one (needed for iPhone offline install)...'
        Push-Location $pwa
        try { node scripts/make-cert.js } finally { Pop-Location }
    }
    $proc = Start-Process node -ArgumentList 'server.js' -WorkingDirectory $pwa -WindowStyle Hidden -PassThru
    $proc.Id | Set-Content $pidFile
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Milliseconds 300
        if ((Get-ServerPids).Count -gt 0) { Show-Status; return }
    }
    throw 'PWA service did not start listening; run "node pwa\server.js" to see the error.'
}

switch ($Action) {
    'start' { Start-Server }
    'stop' { Stop-Server }
    'restart' { Stop-Server; Start-Sleep -Milliseconds 500; Start-Server }
    'status' { Show-Status }
}
