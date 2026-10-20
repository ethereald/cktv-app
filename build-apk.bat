@echo off
setlocal

set "PROJECT_DIR=%~dp0"

if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME if exist "C:\xiaoya-android-tools\sdk\platform-tools\adb.exe" set "ANDROID_HOME=C:\xiaoya-android-tools\sdk"

if not defined ANDROID_HOME (
    echo Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.
    exit /b 1
)

set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
call "%PROJECT_DIR%gradlew.bat" --no-daemon -p "%PROJECT_DIR%." clean assembleDebug lintDebug
if errorlevel 1 goto build_failed

set "APK_DIR=%PROJECT_DIR%app\build\outputs\apk\debug"
set "APK_PATH=%APK_DIR%\app-debug.apk"
if not exist "%APK_PATH%" (
    echo Build completed, but the expected APK was not created:
    echo %APK_PATH%
    exit /b 1
)

for /f "usebackq delims=" %%V in (`powershell.exe -NoProfile -Command "(Get-Content -Raw '%APK_DIR%\output-metadata.json' | ConvertFrom-Json).elements[0].versionName"`) do set "APP_VERSION=%%V"
if not defined APP_VERSION (
    echo Could not read the APK version from Gradle output metadata.
    exit /b 1
)

set "NAMED_APK=%APK_DIR%\CKTV-Android-TV-v%APP_VERSION%.apk"
if exist "%NAMED_APK%" del /q "%NAMED_APK%"
move /y "%APK_PATH%" "%NAMED_APK%" >nul
if errorlevel 1 (
    echo Could not rename the generated APK.
    exit /b 1
)

echo.
echo Build successful:
echo %NAMED_APK%
exit /b 0

:build_failed
echo.
echo Android build failed.
exit /b 1
