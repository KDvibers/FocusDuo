[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,31}$')]
    [string]$Profile,
    [string]$BaseUrl = 'http://127.0.0.1:18091',
    [ValidateRange(840, 4096)][int]$Width = 1280,
    [ValidateRange(600, 4096)][int]$Height = 800,
    [switch]$PrepareOnly
)

$ErrorActionPreference = 'Stop'
$focusDesktopDirectory = Split-Path $PSScriptRoot -Parent
$focusProfileDirectory = Join-Path $focusDesktopDirectory "build/stage8-runtime/profile-$($Profile.ToLowerInvariant())"
$focusClasspathFile = Join-Path $focusProfileDirectory 'classpath.txt'
$focusPreferencesFile = Join-Path $focusProfileDirectory 'preferences.properties'
$focusInitFile = Join-Path $focusProfileDirectory 'export-classpath.gradle'

# Keep this launcher independent of the default user preferences file.
# Only an origin is accepted, matching the application's server-address setting.
$focusServerUri = $null
if (-not [Uri]::TryCreate($BaseUrl.Trim(), [UriKind]::Absolute, [ref]$focusServerUri) -or
    $focusServerUri.Scheme -notin @('http', 'https') -or
    [string]::IsNullOrWhiteSpace($focusServerUri.Host) -or
    $focusServerUri.Port -eq 0 -or
    $focusServerUri.AbsolutePath -ne '/' -or
    $focusServerUri.UserInfo -or $focusServerUri.Query -or $focusServerUri.Fragment) {
    throw 'BaseUrl must be an http:// or https:// origin without credentials, path, query or fragment.'
}
$focusServerAddress = $focusServerUri.GetLeftPart([UriPartial]::Authority)

# Same JDK 21 lookup order as desktop/run.ps1; no permanent environment changes.
$focusCandidates = @($env:FOCUSDUO_JAVA_HOME, $env:JAVA_HOME)
$focusJdksDirectory = Join-Path $env:USERPROFILE '.jdks'
if (Test-Path -LiteralPath $focusJdksDirectory) {
    $focusCandidates += @(Get-ChildItem -Directory -LiteralPath $focusJdksDirectory | Select-Object -ExpandProperty FullName)
}
$focusJavaCommand = Get-Command java -ErrorAction SilentlyContinue
if ($focusJavaCommand) {
    $focusCandidates += Split-Path (Split-Path $focusJavaCommand.Source -Parent) -Parent
}
$focusJdk = $null
foreach ($focusCandidate in $focusCandidates) {
    if ([string]::IsNullOrWhiteSpace($focusCandidate)) { continue }
    $focusRelease = Join-Path $focusCandidate 'release'
    $focusCompiler = Join-Path $focusCandidate 'bin/javac.exe'
    if ((Test-Path -LiteralPath $focusRelease) -and (Test-Path -LiteralPath $focusCompiler)) {
        if (Select-String -LiteralPath $focusRelease -Pattern '^JAVA_VERSION="21(?:\.|\+|")' -Quiet) {
            $focusJdk = $focusCandidate
            break
        }
    }
}
if (-not $focusJdk) {
    throw 'JDK 21 not found. Set FOCUSDUO_JAVA_HOME or JAVA_HOME to your JDK 21 directory.'
}

$focusPreviousJava = $env:JAVA_HOME
$focusPreviousPath = $env:PATH
$focusPreviousBaseUrl = $env:FOCUSDUO_BASE_URL
$focusExitCode = 1
Push-Location $focusDesktopDirectory
try {
    $env:JAVA_HOME = $focusJdk
    $env:PATH = (Join-Path $focusJdk 'bin') + [IO.Path]::PathSeparator + $focusPreviousPath
    $env:FOCUSDUO_BASE_URL = $focusServerAddress
    [IO.Directory]::CreateDirectory($focusProfileDirectory) | Out-Null
    # Give every launch its own immutable application JAR: compiling while another
    # client is running must not replace classes that its JVM has yet to load.
    # Gradle exits before Compose starts, so two clients do not hold Gradle run locks.
    $focusInitSource = @'
gradle.projectsEvaluated {
    def desktop = gradle.rootProject
    desktop.tasks.register('writeFocusDuoIntegrationClasspath') {
        dependsOn desktop.tasks.named('jar')
        doLast {
            def output = new File(System.getProperty('focusduo.integration.classpath'))
            output.parentFile.mkdirs()
            def applicationJar = desktop.tasks.named('jar').get().archiveFile.get().asFile
            def launchJar = new File(output.parentFile, 'client-' + UUID.randomUUID() + '.jar')
            java.nio.file.Files.copy(applicationJar.toPath(), launchJar.toPath())
            output.setText(launchJar.absolutePath + File.pathSeparator + desktop.configurations.runtimeClasspath.asPath, 'UTF-8')
        }
    }
}
'@
    [IO.File]::WriteAllText($focusInitFile, $focusInitSource, [Text.UTF8Encoding]::new($false))
    Write-Host "FocusDuo integration profile '$Profile': preparing with JDK 21 at $focusJdk"
    & ./gradlew.bat -I $focusInitFile writeFocusDuoIntegrationClasspath "-Dfocusduo.integration.classpath=$focusClasspathFile" --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Preparing the Compose runtime failed (exit $LASTEXITCODE)." }
    $focusClasspath = [IO.File]::ReadAllText($focusClasspathFile, [Text.Encoding]::UTF8).Trim()
    if ([string]::IsNullOrWhiteSpace($focusClasspath)) { throw 'The exported runtime classpath is empty.' }
    if ($PrepareOnly) {
        Write-Host "Prepared runtime: $focusClasspathFile"
        $focusExitCode = 0
    } else {
        Write-Host "Starting real client '$Profile' at $focusServerAddress; preferences: $focusPreferencesFile"
        & (Join-Path $focusJdk 'bin/java.exe') "-Dfocusduo.preferences.path=$focusPreferencesFile" '-cp' $focusClasspath 'dev.focusduo.MainKt' "--width=$Width" "--height=$Height"
        $focusExitCode = $LASTEXITCODE
    }
} finally {
    $env:JAVA_HOME = $focusPreviousJava
    $env:PATH = $focusPreviousPath
    $env:FOCUSDUO_BASE_URL = $focusPreviousBaseUrl
    Pop-Location
}
exit $focusExitCode
