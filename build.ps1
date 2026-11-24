Set-Location $PSScriptRoot
if (-not $env:JAVA_HOME -and (Test-Path 'C:\Users\Loong\AppData\Local\Programs\Android Studio\jbr')) {
    $env:JAVA_HOME = 'C:\Users\Loong\AppData\Local\Programs\Android Studio\jbr'
}
& .\gradlew.bat assembleDebug --console=plain --stacktrace
exit $LASTEXITCODE
