[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArguments
)

$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaHome = $env:JAVA_HOME

if (-not [string]::IsNullOrWhiteSpace($javaHome)) {
    $javaExecutable = Join-Path $javaHome 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaExecutable)) {
        throw "JAVA_HOME does not contain bin\java.exe: $javaHome"
    }
} else {
    $portableJavaRoot = Join-Path $projectRoot 'tools\java25'
    if (Test-Path -LiteralPath $portableJavaRoot) {
        $portableJavaHomes = Get-ChildItem -LiteralPath $portableJavaRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'bin\java.exe') } |
            Sort-Object -Property Name -Descending

        if ($portableJavaHomes.Count -gt 0) {
            $javaHome = $portableJavaHomes[0].FullName
        }
    }

    if ([string]::IsNullOrWhiteSpace($javaHome)) {
        $pathJava = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($pathJava) {
            $javaExecutable = $pathJava.Source
            $javaHome = Split-Path -Parent (Split-Path -Parent $javaExecutable)
        }
    }

    if (-not $javaExecutable -and -not [string]::IsNullOrWhiteSpace($javaHome)) {
        $javaExecutable = Join-Path $javaHome 'bin\java.exe'
    }
}

if ([string]::IsNullOrWhiteSpace($javaHome) -or -not (Test-Path -LiteralPath $javaExecutable)) {
    throw 'Install a Java 25 JDK, place it under tools\java25, or set JAVA_HOME to its installation directory.'
}

# Java prints its version to stderr. Windows PowerShell 5.1 turns redirected native stderr into
# error records, which the 'Stop' preference would treat as fatal.
$ErrorActionPreference = 'Continue'
$javaVersionOutput = (& $javaExecutable -version 2>&1 | Out-String)
$javaExitCode = $LASTEXITCODE
$ErrorActionPreference = 'Stop'

if ($javaExitCode -ne 0 -or $javaVersionOutput -notmatch 'version "25(?:\.|\")') {
    throw "The selected Java installation must be Java 25. Found: $javaVersionOutput"
}

$env:JAVA_HOME = $javaHome
$env:PATH = "$(Join-Path $javaHome 'bin');$env:PATH"

$wrapper = Join-Path $projectRoot 'gradlew.bat'
$gradleExitCode = 1
Push-Location -LiteralPath $projectRoot
try {
    # Gradle's stderr must not stop the script when a caller redirects it, for the same reason.
    $ErrorActionPreference = 'Continue'
    & $wrapper @GradleArguments
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
}

exit $gradleExitCode
