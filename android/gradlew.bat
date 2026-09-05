@echo off
setlocal EnableExtensions EnableDelayedExpansion
set GRADLE_VERSION=8.11.1
if defined GRADLE_USER_HOME (
  set CACHE_ROOT=%GRADLE_USER_HOME%\spatial-nomap-wrapper
) else (
  set CACHE_ROOT=%USERPROFILE%\.gradle\spatial-nomap-wrapper
)
set DIST_DIR=%CACHE_ROOT%\gradle-%GRADLE_VERSION%
set ZIP_FILE=%CACHE_ROOT%\gradle-%GRADLE_VERSION%-bin.zip
if not exist "%DIST_DIR%\bin\gradle.bat" (
  if not exist "%CACHE_ROOT%" mkdir "%CACHE_ROOT%"
  if not exist "%ZIP_FILE%" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Invoke-WebRequest -UseBasicParsing 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP_FILE%.tmp'"
    if errorlevel 1 exit /b 1
    move /y "%ZIP_FILE%.tmp" "%ZIP_FILE%" >nul
  )
  if exist "%DIST_DIR%" rmdir /s /q "%DIST_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP_FILE%' -DestinationPath '%CACHE_ROOT%\extract' -Force"
  if errorlevel 1 exit /b 1
  move "%CACHE_ROOT%\extract\gradle-%GRADLE_VERSION%" "%DIST_DIR%" >nul
  rmdir /s /q "%CACHE_ROOT%\extract"
)
call "%DIST_DIR%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
