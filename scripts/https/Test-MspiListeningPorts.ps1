[CmdletBinding()]
param([ValidateRange(1024,65535)][int]$HttpsPort = 8443, [ValidateRange(1,65535)][int]$DatabasePort = 5432)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if (-not (Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue)) { throw 'Esta comprobacion requiere Windows con Get-NetTCPConnection.' }
$ports = @($HttpsPort, $DatabasePort, 8080)
$listeners = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | Where-Object { $_.LocalPort -in $ports })
$listeners | Select-Object LocalAddress,LocalPort,OwningProcess
$exposedDatabase = @($listeners | Where-Object { $_.LocalPort -eq $DatabasePort -and $_.LocalAddress -notin @('127.0.0.1','::1') })
if ($exposedDatabase.Count -gt 0) { throw 'PostgreSQL escucha fuera de loopback. Corrija su configuracion o publicacion de contenedor antes de exponer la aplicacion.' }
if (@($listeners | Where-Object {$_.LocalPort -eq 8080}).Count -gt 0) { Write-Warning 'Hay un proceso escuchando en 8080. Identifique su propietario; MSPI HTTPS no necesita ese listener.' }
Write-Output 'La inspeccion es local y no sustituye una prueba desde otro equipo ni una revision del firewall.'
