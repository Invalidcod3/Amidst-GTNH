@echo off
setlocal

call "%~dp0gradlew.bat" -p "%~dp0." assembleRelease %*
if errorlevel 1 exit /b %errorlevel%

echo.
echo Build completed. Artifacts:
for %%F in ("%~dp0build\release\*.jar") do echo   %%~fF
