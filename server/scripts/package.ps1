param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$taskRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$taskPackage = Get-Content -Raw -LiteralPath (Join-Path $taskRoot 'package.json') | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path (Split-Path -Parent $taskRoot) 'releases'
}
$taskOutput = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $taskOutput | Out-Null
$taskArchive = Join-Path $taskOutput ("Mkusic-server-" + $taskPackage.version + '.zip')
# Explicit allow-list: always distribute a blank library, never .env, user uploads or certificates.
$taskFiles = @('package.json', 'server.js', 'README.md', 'LICENSE', '.env.example', '.gitignore', '.dockerignore', 'Dockerfile', 'compose.yaml', 'start.cmd', 'start.sh', 'data/.gitkeep')
foreach ($taskFolder in @('lib', 'public', 'scripts', 'test', 'deploy')) {
    $taskFiles += Get-ChildItem -File -Recurse -LiteralPath (Join-Path $taskRoot $taskFolder) | ForEach-Object {
        $_.FullName.Substring($taskRoot.Length + 1).Replace('\', '/')
    }
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
Add-Type -AssemblyName System.IO.Compression
$taskZipStream = [IO.File]::Open($taskArchive, [IO.FileMode]::Create, [IO.FileAccess]::Write)
$taskZip = [IO.Compression.ZipArchive]::new($taskZipStream, [IO.Compression.ZipArchiveMode]::Create)
try {
    foreach ($taskRelative in $taskFiles) {
        $taskFile = Join-Path $taskRoot $taskRelative
        [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($taskZip, $taskFile,
            ('Mkusic-server/' + $taskRelative), [IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally { $taskZip.Dispose(); $taskZipStream.Dispose() }
Write-Output $taskArchive
