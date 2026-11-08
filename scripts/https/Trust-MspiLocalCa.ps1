[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$TlsDirectory, [switch]$Remove)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) { throw 'Este comando gestiona solo la confianza del usuario actual de Windows.' }
$path = Join-Path (Resolve-Path -LiteralPath $TlsDirectory).Path 'ca.crt'
$certificate = New-Object Security.Cryptography.X509Certificates.X509Certificate2($path)
$constraints = @($certificate.Extensions | Where-Object {$_.Oid.Value -eq '2.5.29.19'})
if ($certificate.HasPrivateKey -or $certificate.Subject -ne $certificate.Issuer -or $constraints.Count -ne 1 -or
    -not $constraints[0].CertificateAuthority) {
    throw 'Se requiere el certificado publico de la CA local.'
}
if (-not $Remove -and ($certificate.NotAfter -lt [DateTime]::Now -or $certificate.NotBefore -gt [DateTime]::Now)) {
    throw 'No se puede confiar en una CA fuera de su periodo de vigencia.'
}
$store = New-Object Security.Cryptography.X509Certificates.X509Store('Root', 'CurrentUser')
try {
    $store.Open([Security.Cryptography.X509Certificates.OpenFlags]::ReadWrite)
    if ($Remove) { $store.Remove($certificate); $action = 'Removed' }
    else { $store.Add($certificate); $action = 'TrustedForCurrentUser' }
    [pscustomobject]@{Action=$action;Subject=$certificate.Subject;Thumbprint=$certificate.Thumbprint}
} finally { $store.Close(); $certificate.Dispose() }
