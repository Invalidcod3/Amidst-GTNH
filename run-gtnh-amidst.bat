@echo off
setlocal

set "AMIDST_JAR=%~dp0target\amidst-gtnh-biomes-v0-1-v13.jar"

if not exist "%AMIDST_JAR%" (
    echo Amidst GTNH JAR was not found:
    echo   %AMIDST_JAR%
    echo Build the project before using this launcher.
    exit /b 1
)

if defined AMIDST_JAVA_HOME (
    set "JAVA_COMMAND=%AMIDST_JAVA_HOME%\bin\java.exe"
) else if defined JAVA_HOME (
    set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
) else (
    set "JAVA_COMMAND=java"
)

"%JAVA_COMMAND%" -jar "%AMIDST_JAR%" -gtnh-worker %*
