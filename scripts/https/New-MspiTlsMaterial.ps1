[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$LanAddress,
    [string]$KeytoolPath
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$destination = [IO.Path]::GetFullPath($ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($OutputDirectory))
$scan = $destination
while ($scan) {
    if (Test-Path -LiteralPath (Join-Path $scan '.git')) { throw 'El material TLS debe quedar fuera de cualquier repositorio Git.' }
    $scan = [IO.Path]::GetDirectoryName($scan)
}
if (Test-Path -LiteralPath $destination) { throw 'El directorio de destino ya existe; no se sobrescriben certificados.' }
if ($LanAddress) {
    $parsed = $null
    if (-not [Net.IPAddress]::TryParse($LanAddress, [ref]$parsed) -or
        $parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Indique una IPv4 privada para LAN.' }
    $octets = $parsed.GetAddressBytes()
    if (-not ($octets[0] -eq 10 -or ($octets[0] -eq 192 -and $octets[1] -eq 168) -or
        ($octets[0] -eq 172 -and $octets[1] -ge 16 -and $octets[1] -le 31))) { throw 'Indique una IPv4 privada para LAN.' }
    $LanAddress = $parsed.IPAddressToString
}
if (-not $KeytoolPath) {
    if ($env:JAVA_HOME) { $KeytoolPath = Join-Path $env:JAVA_HOME 'bin/keytool' }
    else { $KeytoolPath = (Get-Command keytool -ErrorAction Stop).Source }
}
if (-not (Test-Path -LiteralPath $KeytoolPath) -and (Test-Path -LiteralPath ($KeytoolPath + '.exe'))) { $KeytoolPath += '.exe' }
if (-not (Test-Path -LiteralPath $KeytoolPath)) { throw 'No se encontro keytool en el JDK seleccionado.' }

[void][IO.Directory]::CreateDirectory($destination)
$isWindowsPlatform = [Environment]::OSVersion.Platform -eq [PlatformID]::Win32NT
if ($isWindowsPlatform) {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    $rule = New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow')
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $destination -AclObject $acl
} else {
    & chmod 700 -- $destination
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron restringir los permisos del directorio TLS.' }
}

function New-RandomPassword {
    $bytes = New-Object byte[] 32
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes); return [Convert]::ToBase64String($bytes) }
    finally { $random.Dispose(); [Array]::Clear($bytes, 0, $bytes.Length) }
}
function Invoke-Keytool([string[]]$Arguments) {
    & $KeytoolPath @Arguments *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la generacion o verificacion de certificados. No se muestran argumentos privados.' }
}
$previousCa = $env:MSPI_CERTGEN_CA_PASSWORD
$previousServer = $env:MSPI_CERTGEN_SERVER_PASSWORD
try {
    $env:MSPI_CERTGEN_CA_PASSWORD = New-RandomPassword
    $env:MSPI_CERTGEN_SERVER_PASSWORD = New-RandomPassword
    $utf8 = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $destination 'ca-password.txt'), $env:MSPI_CERTGEN_CA_PASSWORD, $utf8)
    [IO.File]::WriteAllText((Join-Path $destination 'server-password.txt'), $env:MSPI_CERTGEN_SERVER_PASSWORD, $utf8)
    $ca = Join-Path $destination 'ca.p12'
    $server = Join-Path $destination 'server.p12'
    $caCert = Join-Path $destination 'ca.crt'
    $csr = Join-Path $destination 'server.csr'
    $certificate = Join-Path $destination 'server.crt'
    $san = 'SAN=DNS:localhost,IP:127.0.0.1'
    if ($LanAddress) { $san += ',IP:' + $LanAddress }
    Invoke-Keytool -Arguments @('-genkeypair','-alias','mspi-ca','-keyalg','RSA','-keysize','3072','-sigalg','SHA256withRSA',
        '-dname','CN=MSPI Local Development CA','-validity','365','-ext','BC:c=ca:true,pathlen:0',
        '-ext','KU:c=keyCertSign,cRLSign','-keystore',$ca,'-storetype','PKCS12','-storepass:env','MSPI_CERTGEN_CA_PASSWORD')
    Invoke-Keytool -Arguments @('-exportcert','-rfc','-alias','mspi-ca','-keystore',$ca,
        '-storepass:env','MSPI_CERTGEN_CA_PASSWORD','-file',$caCert)
    Invoke-Keytool -Arguments @('-genkeypair','-alias','mspi-server','-keyalg','RSA','-keysize','3072','-sigalg','SHA256withRSA',
        '-dname','CN=localhost','-validity','90','-ext','BC:c=ca:false','-ext','EKU=serverAuth','-ext',$san,
        '-keystore',$server,'-storetype','PKCS12','-storepass:env','MSPI_CERTGEN_SERVER_PASSWORD')
    Invoke-Keytool -Arguments @('-certreq','-alias','mspi-server','-keystore',$server,
        '-storepass:env','MSPI_CERTGEN_SERVER_PASSWORD','-file',$csr)
    Invoke-Keytool -Arguments @('-gencert','-rfc','-alias','mspi-ca','-keystore',$ca,
        '-storepass:env','MSPI_CERTGEN_CA_PASSWORD','-infile',$csr,'-outfile',$certificate,'-validity','90',
        '-ext','BC:c=ca:false','-ext','KU:c=digitalSignature,keyEncipherment','-ext','EKU=serverAuth','-ext',$san)
    Invoke-Keytool -Arguments @('-importcert','-noprompt','-alias','mspi-ca','-keystore',$server,
        '-storepass:env','MSPI_CERTGEN_SERVER_PASSWORD','-file',$caCert)
    Invoke-Keytool -Arguments @('-importcert','-noprompt','-alias','mspi-server','-keystore',$server,
        '-storepass:env','MSPI_CERTGEN_SERVER_PASSWORD','-file',$certificate)
    Invoke-Keytool -Arguments @('-list','-alias','mspi-server','-keystore',$server,'-storepass:env','MSPI_CERTGEN_SERVER_PASSWORD')
    [pscustomobject]@{ Directory = $destination; PublicCA = $caCert; KeyStore = $server; PublicCertificate = $certificate }
} finally {
    $env:MSPI_CERTGEN_CA_PASSWORD = $previousCa
    $env:MSPI_CERTGEN_SERVER_PASSWORD = $previousServer
}
