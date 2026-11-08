[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$TlsDirectory,
    [ValidateSet('local', 'lan', 'internet')][string]$Mode = 'local',
    [string]$LanAddress,
    [ValidateRange(1024,65535)][int]$Port = 8443,
    [string]$JarPath = (Join-Path $PSScriptRoot '../../mspi/target/mspi-0.0.1-SNAPSHOT.jar'),
    [string]$JavaPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$directory = (Resolve-Path -LiteralPath $TlsDirectory).Path
$scan = $directory
while ($scan) {
    if (Test-Path -LiteralPath (Join-Path $scan '.git')) { throw 'El material TLS debe quedar fuera de cualquier repositorio Git.' }
    if (Test-Path -LiteralPath $scan) {
        $entry = Get-Item -LiteralPath $scan -Force
        if (($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw 'No se permiten enlaces o junctions en la ruta del material TLS.'
        }
    }
    $scan = [IO.Path]::GetDirectoryName($scan)
}
$store = Join-Path $directory 'server.p12'
$passwordFile = Join-Path $directory 'server-password.txt'
if (-not (Test-Path -LiteralPath $store -PathType Leaf) -or -not (Test-Path -LiteralPath $passwordFile -PathType Leaf)) {
    throw 'No se encontro material TLS completo en el directorio privado.'
}
if (-not (Test-Path -LiteralPath $JarPath -PathType Leaf)) { throw 'Construya primero el JAR con JDK 17.' }
if ($Mode -eq 'lan' -and -not $LanAddress) { throw 'El modo LAN requiere una IP privada explicita incluida en el certificado.' }
if (-not $JavaPath) {
    if ($env:JAVA_HOME) { $JavaPath = Join-Path $env:JAVA_HOME 'bin/java' }
    else { $JavaPath = (Get-Command java -ErrorAction Stop).Source }
}
$version = & $JavaPath -version 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "17\.') { throw 'Seleccione JDK 17 para ejecutar MSPI.' }
$names = @('MSPI_TLS_KEYSTORE','MSPI_TLS_KEYSTORE_PASSWORD','MSPI_HTTPS_PORT','MSPI_HTTPS_LAN_ADDRESS')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
try {
    $env:MSPI_TLS_KEYSTORE = ([Uri]([IO.Path]::GetFullPath($store))).AbsoluteUri
    $env:MSPI_TLS_KEYSTORE_PASSWORD = [IO.File]::ReadAllText($passwordFile).Trim()
    $env:MSPI_HTTPS_PORT = $Port.ToString()
    $env:MSPI_HTTPS_LAN_ADDRESS = $LanAddress
    & $JavaPath -jar $JarPath ('--spring.profiles.active=https-' + $Mode)
    if ($LASTEXITCODE -ne 0) { throw 'El backend no pudo iniciar en el perfil HTTPS solicitado.' }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
}
