$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Split-Path -Parent $project
$wrapper = Join-Path $project 'gradle\wrapper\gradle-wrapper.jar'
$resultFile = Join-Path $root 'release-result.txt'

function Fail([string]$Message) { Write-Host "ERROR: $Message" -ForegroundColor Red; exit 1 }
function Run-Gradle([string[]]$Tasks) {
    & java -cp $wrapper org.gradle.wrapper.GradleWrapperMain @Tasks '--console=plain'
    if ($LASTEXITCODE -ne 0) { Fail "Gradle step failed: $($Tasks -join ' ')" }
}

function Get-XmlCount([string]$Tag, [string]$Name) {
    $match = [regex]::Match($Tag, "\b$([regex]::Escape($Name))=`"(\d+)`"")
    if (-not $match.Success) { Fail "JUnit result is missing '$Name'." }
    return [int]$match.Groups[1].Value
}

function Read-JUnitResult {
    $directory = Join-Path $project 'build\test-results\test'
    $files = @(Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml' -File -ErrorAction SilentlyContinue)
    if ($files.Count -eq 0) { Fail 'JUnit XML results are missing.' }
    $tests = 0; $failures = 0; $errors = 0; $skipped = 0
    foreach ($file in $files) {
        $raw = Get-Content -Raw -LiteralPath $file.FullName
        $suite = [regex]::Match($raw, '<testsuite\b[^>]*>').Value
        if ([string]::IsNullOrWhiteSpace($suite)) { Fail "Invalid JUnit XML: $($file.Name)" }
        $tests += Get-XmlCount $suite 'tests'
        $failures += Get-XmlCount $suite 'failures'
        $errors += Get-XmlCount $suite 'errors'
        $skipped += Get-XmlCount $suite 'skipped'
    }
    if ($failures -ne 0 -or $errors -ne 0) {
        Fail "JUnit failures remain: tests=$tests failures=$failures errors=$errors skipped=$skipped."
    }
    return [pscustomobject]@{ Tests = $tests; Failures = $failures; Errors = $errors; Skipped = $skipped; Suites = $files.Count }
}

function Remove-GeneratedTree([string]$Target) {
    $projectFull = [IO.Path]::GetFullPath($project).TrimEnd('\', '/')
    $targetFull = [IO.Path]::GetFullPath($Target).TrimEnd('\', '/')
    $prefix = $projectFull + [IO.Path]::DirectorySeparatorChar
    if ($targetFull -eq $projectFull -or
            -not $targetFull.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
        Fail "Refusing to remove path outside the project: $targetFull"
    }
    if (Test-Path -LiteralPath $targetFull) {
        Remove-Item -LiteralPath $targetFull -Recurse -Force
    }
}

function Read-GameTestCount {
    $log = Join-Path $project 'run-gametest\logs\latest.log'
    if (-not (Test-Path -LiteralPath $log)) { Fail 'GameTest latest.log is missing.' }
    $raw = Get-Content -Raw -LiteralPath $log
    $match = [regex]::Match($raw, 'All\s+(\d+)\s+required tests passed', 'IgnoreCase')
    if (-not $match.Success) { Fail 'Forge GameTest success summary is missing.' }
    return [int]$match.Groups[1].Value
}

function Read-ModVersions([string]$Toml) {
    $versions = @{}
    $blocks = [regex]::Matches($Toml, '(?ms)^\s*\[\[mods\]\]\s*(.*?)(?=^\s*\[\[|\z)')
    foreach ($blockMatch in $blocks) {
        $block = $blockMatch.Groups[1].Value
        $id = [regex]::Match($block, '(?m)^\s*modId\s*=\s*"([^"]+)"')
        $versionMatch = [regex]::Match($block, '(?m)^\s*version\s*=\s*"([^"]+)"')
        if ($id.Success -and $versionMatch.Success) {
            $versions[$id.Groups[1].Value] = $versionMatch.Groups[1].Value
        }
    }
    return $versions
}

function Assert-SafeArchive([IO.FileInfo]$Jar, [string[]]$Entries) {
    $forbiddenFragments = @('companion-ai.json','task-state.json','.git/','.idea/','crash-reports/','aifailure/')
    $sensitiveName = '(?i)(^|/)(?:\.env(?:\.[^/]*)?|credentials?(?:\.[^/]*)?|secrets?(?:\.[^/]*)?|id_(?:rsa|dsa|ecdsa|ed25519)(?:\.[^/]*)?|private[-_]?key(?:\.[^/]*)?)$'
    $sensitiveExtension = '(?i)\.(?:pem|key|p12|pfx|jks|keystore|bak|backup|old|orig|tmp|temp|swp|swo)$'
    foreach ($entry in $Entries) {
        $normalized = $entry.Replace('\', '/')
        $lower = $normalized.ToLowerInvariant()
        foreach ($fragment in $forbiddenFragments) {
            if ($lower.Contains($fragment.ToLowerInvariant())) { Fail "Forbidden JAR content: $entry" }
        }
        if ($normalized -match $sensitiveName -or $normalized -match $sensitiveExtension) {
            Fail "Sensitive or temporary filename in JAR: $entry"
        }
    }

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($Jar.FullName)
    try {
        $textExtensions = @('.json','.toml','.cfg','.conf','.properties','.txt','.md','.xml','.yml','.yaml','.mcmeta','.lang','.csv','.mf')
        foreach ($entry in $archive.Entries) {
            if ([string]::IsNullOrEmpty($entry.Name)) { continue }
            $stream = $entry.Open()
            $memory = [IO.MemoryStream]::new()
            try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
            $bytes = $memory.ToArray()
            $memory.Dispose()
            $ascii = [Text.Encoding]::ASCII.GetString($bytes)
            if ($ascii -match '(?i)(?:^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{8,}') {
                Fail "Possible API key embedded in JAR entry: $($entry.FullName)"
            }
            if ($ascii -match '-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----') {
                Fail "Private-key material embedded in JAR entry: $($entry.FullName)"
            }
            if ($ascii -match '(?i)\b(?:Authorization|Proxy-Authorization)\s*[:=]\s*Bearer\s+[A-Za-z0-9._~+/-]{12,}={0,2}\b') {
                Fail "Possible authorization header embedded in JAR entry: $($entry.FullName)"
            }
            if ($ascii -match '(?i)\b(?:Cookie|Set-Cookie)\s*[:=]\s*[A-Za-z0-9._~+/%-]{12,}') {
                Fail "Possible cookie embedded in JAR entry: $($entry.FullName)"
            }
            if ($ascii -match '(?i)\bBearer\s+[A-Za-z0-9._~+/-]{12,}={0,2}\b') {
                Fail "Possible bearer token embedded in JAR entry: $($entry.FullName)"
            }
            $extension = [IO.Path]::GetExtension($entry.FullName).ToLowerInvariant()
            if ($textExtensions -notcontains $extension) { continue }
            $text = [Text.Encoding]::UTF8.GetString($bytes)
            if ($text -match '(?im)(?:^|[\r\n{,])\s*["'']?(?:authorization|proxy-authorization|cookie|set-cookie)["'']?\s*[:=]\s*["'']?(?!\s*(?:<redacted>|<token>|\$\{|\{\{|\[redacted\]|bearer\s*(?:<|\$|\{)))[^\r\n"'',}]{6,}') {
                Fail "Possible Authorization/Cookie value in JAR entry: $($entry.FullName)"
            }
        }
    } finally {
        $archive.Dispose()
    }
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Fail 'Java is unavailable.' }
if (-not (Get-Command git -ErrorAction SilentlyContinue)) { Fail 'Git is unavailable.' }
if (-not (Test-Path -LiteralPath $wrapper)) { Fail 'Gradle Wrapper JAR is missing.' }

Push-Location $project
try {
    $versionLine = Select-String -LiteralPath 'build.gradle' -Pattern "^version\s*=\s*'([^']+)'$" | Select-Object -First 1
    if (-not $versionLine) { Fail 'Cannot read version from build.gradle.' }
    $version = $versionLine.Matches[0].Groups[1].Value
    $apiVersionLine = Select-String -LiteralPath 'build.gradle' -Pattern "^ext\.numenApiVersion\s*=\s*'([^']+)'$" | Select-Object -First 1
    if (-not $apiVersionLine) { Fail 'Cannot read Numen API version from build.gradle.' }
    $apiVersion = $apiVersionLine.Matches[0].Groups[1].Value
    $commit = (& git rev-parse --short HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { Fail 'Cannot read Git commit.' }
    Write-Host "Version: $version  Commit: $commit"
    $dirty = @(& git status --porcelain --untracked-files=all)
    if ($LASTEXITCODE -ne 0) { Fail 'Cannot inspect Git worktree.' }
    if ($dirty.Count -gt 0) { $dirty | ForEach-Object { Write-Host $_ }; Fail 'Core worktree is dirty; release stopped.' }

    Run-Gradle @('compileJava')
    Run-Gradle @('test')
    $junit = Read-JUnitResult
    Remove-GeneratedTree (Join-Path $project 'run-gametest\world')
    Run-Gradle @('runGameTestServer')
    $gameTests = Read-GameTestCount
    Run-Gradle @('clean', 'build')
    $junit = Read-JUnitResult

    $processedToml = Join-Path $project 'build\resources\main\META-INF\mods.toml'
    if (-not (Test-Path $processedToml)) { Fail 'Processed mods.toml is missing.' }
    $toml = Get-Content -Raw -LiteralPath $processedToml
    $modVersions = Read-ModVersions $toml
    if ($modVersions['numen'] -ne $version) { Fail "Numen version mismatch: expected=$version actual=$($modVersions['numen'])" }
    if ($modVersions['numen_api'] -ne $apiVersion) { Fail "Numen API version mismatch: expected=$apiVersion actual=$($modVersions['numen_api'])" }
    if ($toml -notmatch 'dwinovo, laodafeijibei') { Fail 'Author field is incomplete.' }

    $candidates = @(Get-ChildItem -LiteralPath (Join-Path $project 'build\libs') -Filter '*.jar' -File |
        Where-Object { $_.Name -notmatch '(sources|dev|plain|slim)' } | Sort-Object Length -Descending)
    if ($candidates.Count -eq 0) { Fail 'Final JAR was not found.' }
    $jar = $candidates[0]
    $entries = @(& jar tf $jar.FullName)
    if ($LASTEXITCODE -ne 0) { Fail 'Cannot inspect final JAR.' }
    $required = @(
        'META-INF/mods.toml', 'com/dwinovo/numen/NumenForgeMod.class',
        'com/dwinovo/numen/core/NumenCoreForge.class', 'com/dwinovo/numen/platform/ForgeNetworkChannel.class',
        'com/dwinovo/numen/inventory/CompanionInventoryMenu.class', 'com/dwinovo/numen/client/agent/ToolRouter.class',
        'com/dwinovo/numen/core/task/TaskStateStore.class', 'com/dwinovo/numen/core/task/ResourceLeaseManager.class'
    )
    foreach ($entry in $required) { if ($entries -notcontains $entry) { Fail "JAR entry missing: $entry" } }
    $skills = @($entries | Where-Object { $_ -match '^skills/[^/]+/SKILL\.md$' })
    $compactSkills = @($entries | Where-Object { $_ -match '^skills/[^/]+/SKILL\.compact\.md$' })
    if ($skills.Count -ne 13 -or $compactSkills.Count -ne 13) { Fail "Skill counts differ: full=$($skills.Count), compact=$($compactSkills.Count)." }
    Assert-SafeArchive $jar $entries

    $output = Join-Path $root "numen-1.20.1-$version-bundled.jar"
    Copy-Item -LiteralPath $jar.FullName -Destination $output -Force
    $hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash
    $size = (Get-Item -LiteralPath $output).Length
    $result = @("Version: $version", "Git commit: $commit", "Build time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss K')",
        "JAR path: $output", "File size: $size bytes", "SHA-256: $hash",
        "JUnit: $($junit.Tests) tests, $($junit.Failures) failures, $($junit.Errors) errors, $($junit.Skipped) skipped, $($junit.Suites) suites",
        "Forge GameTest: $gameTests required tests passed",
        "Metadata: numen=$version, numen_api=$apiVersion",
        'Passed: compileJava, JUnit, Forge GameTest, clean build, metadata, authors, critical entries, 26 skills, secret/sensitive-file scan') -join [Environment]::NewLine
    Set-Content -LiteralPath $resultFile -Value $result -Encoding UTF8
    Write-Host "Release succeeded: $output" -ForegroundColor Green
    Write-Host "SHA-256: $hash" -ForegroundColor Green
} finally { Pop-Location }
