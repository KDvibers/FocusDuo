#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateSet('version', 'check', 'createDistributable', 'runDistributable', 'packageMsi', 'suggestRuntimeModules')]
    [string]$Task = 'packageMsi'
)

$ErrorActionPreference = 'Stop'
if (-not [Runtime.InteropServices.RuntimeInformation]::IsOSPlatform([Runtime.InteropServices.OSPlatform]::Windows)) {
    throw 'Build the Windows package on native Windows, not WSL/Linux.'
}
if ([Runtime.InteropServices.RuntimeInformation]::OSArchitecture -ne [Runtime.InteropServices.Architecture]::X64 -or
    -not [Environment]::Is64BitProcess) {
    throw 'Windows x64 and PowerShell x64 are required for this package.'
}

# Match run.ps1: explicit app JDK, JAVA_HOME, user .jdks, then java from PATH.
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
    $focusTools = @('java.exe', 'javac.exe', 'jpackage.exe', 'jlink.exe')
    $focusHasTools = @($focusTools | Where-Object {
        -not (Test-Path -LiteralPath (Join-Path $focusCandidate "bin/$_") -PathType Leaf)
    }).Count -eq 0
    if ((Test-Path -LiteralPath $focusRelease -PathType Leaf) -and $focusHasTools -and
        (Select-String -LiteralPath $focusRelease -Pattern '^JAVA_VERSION="21(?:\.|\+|")' -Quiet) -and
        (Select-String -LiteralPath $focusRelease -Pattern '^OS_NAME="Windows"$' -Quiet) -and
        (Select-String -LiteralPath $focusRelease -Pattern '^OS_ARCH="(?:amd64|x86_64|x64)"$' -Quiet)) {
        $focusJdk = (Resolve-Path -LiteralPath $focusCandidate).Path
        break
    }
}
if (-not $focusJdk) {
    throw 'Windows x64 JDK 21 with javac, jpackage and jlink not found. Set FOCUSDUO_JAVA_HOME or JAVA_HOME.'
}

if (-not [string]::IsNullOrWhiteSpace($env:WIX_PATH)) {
    foreach ($focusWixTool in @('candle.exe', 'light.exe')) {
        if (-not (Test-Path -LiteralPath (Join-Path $env:WIX_PATH $focusWixTool) -PathType Leaf)) {
            throw "WIX_PATH must point to the WiX 3 directory containing candle.exe and light.exe: $env:WIX_PATH"
        }
    }
    Write-Host "FocusDuo: using WiX from WIX_PATH=$env:WIX_PATH"
} else {
    Write-Host 'FocusDuo: Compose 1.9.1 will use its WiX 3.11.2 download/cache when MSI packaging requires it.'
}

$focusPreviousJava = $env:JAVA_HOME
$focusPreviousPath = $env:PATH
$focusExitCode = 1
Push-Location $PSScriptRoot
try {
    $env:JAVA_HOME = $focusJdk
    $env:PATH = (Join-Path $focusJdk 'bin') + [IO.Path]::PathSeparator + $focusPreviousPath
    Write-Host "FocusDuo: native Windows x64; JDK 21 at $focusJdk; task $Task"
    $focusGradleArguments = @('--console=plain', "-Pfocusduo.javaHome=$focusJdk")
    if ($Task -eq 'version') {
        $focusGradleArguments += '--version'
    } else {
        $focusGradleArguments += $Task
    }
    & ./gradlew.bat @focusGradleArguments
    $focusExitCode = $LASTEXITCODE
    if ($focusExitCode -eq 0 -and $Task -eq 'packageMsi') {
        Write-Host 'FocusDuo MSI output: desktop/build/compose/binaries/main/msi/'
    }
} finally {
    $env:JAVA_HOME = $focusPreviousJava
    $env:PATH = $focusPreviousPath
    Pop-Location
}
exit $focusExitCode
