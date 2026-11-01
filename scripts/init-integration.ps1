$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $projectDirectory '.env.integration'
if (Test-Path -LiteralPath $environmentFile) { Write-Output 'Existing .env.integration preserved.'; exit 0 }
function New-LocalToken {
	$bytes = New-Object byte[] 32
	$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
	try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
	return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
}
$settings = "PIP_CORE_TOKEN=$(New-LocalToken)`nPIP_EXPORT_TOKEN=$(New-LocalToken)`nPIP_ANALYST_TOKEN=$(New-LocalToken)`nPIP_CORE_URL=http://127.0.0.1:8080`n"
try {
	$stream = [IO.File]::Open($environmentFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
} catch [IO.IOException] {
	if (Test-Path -LiteralPath $environmentFile) { Write-Output 'Existing .env.integration preserved.'; exit 0 }
	throw
}
try { $bytes = (New-Object Text.UTF8Encoding($false)).GetBytes($settings); $stream.Write($bytes, 0, $bytes.Length) }
finally { $stream.Dispose() }
Write-Output 'Created separate random integration credentials. Values are not printed.'
