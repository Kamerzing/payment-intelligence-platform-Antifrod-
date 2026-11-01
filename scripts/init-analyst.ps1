$ErrorActionPreference = 'Stop'
$environmentFile = Join-Path (Split-Path -Parent $PSScriptRoot) '.env.integration'
if (!(Test-Path -LiteralPath $environmentFile)) { throw 'Run init-integration.ps1 first.' }
$stream = [IO.File]::Open($environmentFile, [IO.FileMode]::Open, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
	$reader = New-Object IO.StreamReader($stream, (New-Object Text.UTF8Encoding($false)), $true, 1024, $true)
	try { $existing = $reader.ReadToEnd() } finally { $reader.Dispose() }
	if ($existing -match '(?m)^PIP_ANALYST_TOKEN=') { Write-Output 'Existing analyst credential preserved.'; exit 0 }
	$bytes = New-Object byte[] 32
	$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
	try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
	$token = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
	$stream.Seek(0, [IO.SeekOrigin]::End) | Out-Null
	$append = (New-Object Text.UTF8Encoding($false)).GetBytes("`nPIP_ANALYST_TOKEN=$token`n")
	$stream.Write($append, 0, $append.Length)
} finally { $stream.Dispose() }
Write-Output 'Added a separate random analyst credential; existing values preserved.'
