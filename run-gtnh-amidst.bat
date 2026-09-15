@echo off
setlocal

rem Read the build filename from the same metadata Gradle uses.
for /f "usebackq tokens=1,* delims==" %%A in ("%~dp0src\main\resources\amidst\metadata.properties") do (
    if "%%A"=="amidst.build.filename" set "AMIDST_JAR=%~dp0build\release\%%B.jar"
)

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

"%JAVA_COMMAND%" -jar "%AMIDST_JAR%" %*
set "VIEWER_EXIT_CODE=%ERRORLEVEL%"
exit /b %VIEWER_EXIT_CODE%
