$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $PSScriptRoot
$environmentFile = Join-Path $projectDirectory '.env'

if (Test-Path -LiteralPath $environmentFile) {
	Write-Output 'Existing .env preserved.'
	exit 0
}

$randomBytes = New-Object byte[] 32
$generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $generator.GetBytes($randomBytes) } finally { $generator.Dispose() }
$databasePassword = [BitConverter]::ToString($randomBytes).Replace('-', '').ToLowerInvariant()
$template = [System.IO.File]::ReadAllText((Join-Path $projectDirectory '.env.example'))
$settings = $template.Replace('PIP_DB_PASSWORD=', "PIP_DB_PASSWORD=$databasePassword")

# CreateNew also preserves a file created by a concurrent invocation.
try {
	$stream = [System.IO.File]::Open($environmentFile, [System.IO.FileMode]::CreateNew,
		[System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
} catch [System.IO.IOException] {
	if (Test-Path -LiteralPath $environmentFile) { Write-Output 'Existing .env preserved.'; exit 0 }
	throw
}
try {
	$bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($settings)
	$stream.Write($bytes, 0, $bytes.Length)
} finally { $stream.Dispose() }
Write-Output 'Created .env with a random password. The password is not printed.'
