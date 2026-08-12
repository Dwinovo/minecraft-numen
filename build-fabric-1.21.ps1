param(
    [switch]$SkipTests,
    [switch]$Clean
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$bundledJdk = Join-Path (Split-Path $projectRoot -Parent) '.jdk\jdk-21.0.12+8'

if (-not $env:JAVA_HOME -and (Test-Path (Join-Path $bundledJdk 'bin\java.exe'))) {
    $env:JAVA_HOME = $bundledJdk
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    throw 'JDK 21 was not found. Set JAVA_HOME or place it at ..\.jdk\jdk-21.0.12+8.'
}

$javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe'
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersionOutput = & $javaExe -version 2>&1
$javaExitCode = $LASTEXITCODE
$ErrorActionPreference = $previousErrorAction
if ($javaExitCode -ne 0) {
    throw "Unable to run Java at $javaExe."
}
$javaMajor = $javaVersionOutput | Select-Object -First 1
if ($javaMajor -notmatch 'version "21\.') {
    throw "Minecraft 1.21 requires JDK 21. Found: $javaMajor"
}

$env:Path = "$(Join-Path $env:JAVA_HOME 'bin');$env:Path"
$tasks = @()
if ($Clean) {
    $tasks += 'clean'
}
$tasks += ':core:fabric:build'
if (-not $SkipTests) {
    $tasks += ':api:common:test'
    $tasks += ':core:common:test'
    $tasks += ':ui:test'
}

& (Join-Path $projectRoot 'gradlew.bat') @tasks '-PfabricOnly' '--no-daemon' '--configuration-cache' '-x' 'javadoc' '--console=plain'
if ($LASTEXITCODE -ne 0) {
    throw "Fabric build failed with exit code $LASTEXITCODE."
}

$jar = Get-ChildItem (Join-Path $projectRoot 'core\fabric\build\libs') -Filter '*.jar' |
    Where-Object { $_.Name -notmatch '-(sources|javadoc|dev)\.jar$' } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) {
    throw 'Build succeeded but no publishable Fabric JAR was found.'
}
if ($jar.Name -notmatch '0\.1\.3') {
    throw "Expected a 0.1.3 artifact, found $($jar.Name)."
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$requiredKeys = @(
    'numen.tab.chat',
    'numen.tab.status',
    'numen.tab.settings',
    'numen.empty.no_companions',
    'numen.provider.title',
    'numen.settings.nav.brain',
    'numen.brain.title',
    'numen.brain.endpoint',
    'numen.voice.title'
)

function Read-VerifiedLanguages {
    param(
        [System.IO.Compression.ZipArchive]$Archive,
        [string]$Prefix,
        [string]$Label
    )

    $languages = @{}
    foreach ($locale in @('en_us', 'zh_cn')) {
        $languageEntry = $Archive.GetEntry("$Prefix/$locale.json")
        if (-not $languageEntry) {
            throw "$Label is missing $Prefix/$locale.json."
        }
        $reader = [System.IO.StreamReader]::new($languageEntry.Open(), [System.Text.Encoding]::UTF8)
        try {
            $translations = $reader.ReadToEnd() | ConvertFrom-Json
        } finally {
            $reader.Dispose()
        }
        $properties = @($translations.PSObject.Properties)
        foreach ($property in $properties) {
            if ([string]::IsNullOrWhiteSpace([string]$property.Value) -or
                    [string]$property.Value -eq $property.Name) {
                throw "Translation $($property.Name) has no actual text in $Label/$locale."
            }
        }
        foreach ($key in $requiredKeys) {
            if (-not $translations.PSObject.Properties[$key]) {
                throw "Translation $key is missing from $Label/$locale."
            }
        }
        $languages[$locale] = $properties
    }

    $enKeys = @($languages.en_us.Name | Sort-Object)
    $zhKeys = @($languages.zh_cn.Name | Sort-Object)
    $difference = @(Compare-Object $enKeys $zhKeys)
    if ($difference.Count -ne 0) {
        throw "English and Chinese key sets differ in $Label."
    }
    if ($enKeys.Count -lt 250) {
        throw "Expected at least 250 translations in $Label, found $($enKeys.Count)."
    }
    return $enKeys.Count
}

$outerJar = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $metadataEntry = $outerJar.GetEntry('fabric.mod.json')
    if (-not $metadataEntry) {
        throw 'Publishable JAR is missing fabric.mod.json.'
    }
    $metadataReader = [System.IO.StreamReader]::new($metadataEntry.Open(), [System.Text.Encoding]::UTF8)
    try {
        $metadata = $metadataReader.ReadToEnd() | ConvertFrom-Json
    } finally {
        $metadataReader.Dispose()
    }
    if ([string]$metadata.version -ne '0.1.3') {
        throw "Expected fabric.mod.json version 0.1.3, found $($metadata.version)."
    }

    $outerKeyCount = Read-VerifiedLanguages $outerJar 'assets/numen/lang' 'outer Numen JAR'
    $apiJarEntry = $outerJar.Entries |
        Where-Object { $_.FullName -like 'META-INF/jars/numen_api*.jar' } |
        Select-Object -First 1
    if (-not $apiJarEntry) {
        throw 'Publishable JAR does not contain the bundled Numen API JAR.'
    }

    $memory = [System.IO.MemoryStream]::new()
    $apiStream = $apiJarEntry.Open()
    try {
        $apiStream.CopyTo($memory)
    } finally {
        $apiStream.Dispose()
    }
    $memory.Position = 0
    $apiJar = [System.IO.Compression.ZipArchive]::new(
        $memory, [System.IO.Compression.ZipArchiveMode]::Read, $false)
    try {
        $apiKeyCount = Read-VerifiedLanguages $apiJar 'assets/numen_api/lang' 'bundled Numen API JAR'
        if ($apiKeyCount -ne $outerKeyCount) {
            throw "Outer and bundled language key counts differ: $outerKeyCount vs $apiKeyCount."
        }
    } finally {
        $apiJar.Dispose()
        $memory.Dispose()
    }
} finally {
    $outerJar.Dispose()
}

Write-Output "FABRIC_JAR=$($jar.FullName)"
Write-Output "VERSION=$($metadata.version)"
Write-Output "LANGUAGES=en_us,zh_cn; KEYS=$outerKeyCount (verified in outer and bundled JARs)"
