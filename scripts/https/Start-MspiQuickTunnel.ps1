[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$TlsDirectory,
    [ValidateRange(1024,65535)][int]$Port = 8443,
    [string]$CloudflaredPath,
    [switch]$ShowCommand
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not $CloudflaredPath) { $CloudflaredPath = (Get-Command cloudflared -ErrorAction Stop).Source }
$version = & $CloudflaredPath --version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version 2026\.10\.7(?:\s|$)') {
    throw 'Esta receta se verifico con cloudflared 2026.10.7. Revise otra version antes de actualizarla.'
}
$ca = Join-Path (Resolve-Path -LiteralPath $TlsDirectory).Path 'ca.crt'
if (-not (Test-Path -LiteralPath $ca -PathType Leaf)) { throw 'No se encontro la CA publica local.' }
$arguments = @('tunnel','--no-autoupdate','--url',('https://127.0.0.1:' + $Port),
    '--origin-ca-pool',$ca,'--origin-server-name','localhost','--no-tls-verify=false',
    '--proxy-connect-timeout','5s','--proxy-tls-timeout','5s')
if ($ShowCommand) {
    [pscustomobject]@{Program=$CloudflaredPath;Arguments=$arguments;PublishesInternet=$false}
    return
}
# Normal certificate validation is mandatory; never bypass it for the preflight.
$health = Invoke-WebRequest -Uri ('https://127.0.0.1:' + $Port + '/api/v1') -TimeoutSec 10 -UseBasicParsing
if ($health.StatusCode -ne 200) { throw 'El backend HTTPS no esta disponible.' }
Write-Output 'Se publicara un enlace temporal al backend. Ctrl+C cierra el tunel. No publique credenciales ni datos reales de evaluacion.'
& $CloudflaredPath @arguments
if ($LASTEXITCODE -ne 0) { throw 'El tunel no pudo iniciarse o termino con error.' }
