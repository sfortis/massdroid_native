@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "BUILD_ROOT="
if defined MASSDROID_BUILD_DIR set "BUILD_ROOT=%MASSDROID_BUILD_DIR%"
if not defined BUILD_ROOT set "BUILD_ROOT=%USERPROFILE%\massdroid-native-build"

if /i "%~1"=="-h" goto :usage
if /i "%~1"=="--help" goto :usage
if /i "%~1"=="-b" (
  if "%~2"=="" (
    echo [ERROR] Missing value for %~1
    goto :usage_error
  )
  set "BUILD_ROOT=%~2"
  shift
  shift
) else if /i "%~1"=="--build-root" (
  if "%~2"=="" (
    echo [ERROR] Missing value for %~1
    goto :usage_error
  )
  set "BUILD_ROOT=%~2"
  shift
  shift
) else if not "%~1"=="" (
  echo [ERROR] Unknown argument: %~1
  goto :usage_error
)

pushd "%SCRIPT_DIR%" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Could not enter script directory: %SCRIPT_DIR%
  exit /b 1
)

call :resolveGradleRunner
if errorlevel 1 goto :fail

call :resolveAdb
if errorlevel 1 goto :fail

call :resolveApkPath
if errorlevel 1 goto :fail

echo === Detekt ===
call :runGradle -PmassdroidBuildRoot="%BUILD_ROOT%" detekt
if errorlevel 1 goto :fail

echo === Build ===
call :runGradle -PmassdroidBuildRoot="%BUILD_ROOT%" assembleDebug --no-build-cache
if errorlevel 1 goto :fail

if not exist "%APK_PATH%" (
  echo [ERROR] APK not found: "%APK_PATH%"
  exit /b 1
)

echo === Install ===
echo Using adb: %ADB_BIN%
"%ADB_BIN%" install -r "%APK_PATH%"
if errorlevel 1 goto :fail

echo === Done ===
popd >nul 2>&1
exit /b 0

:resolveGradleRunner
set "GRADLE_MODE="
if exist "%SCRIPT_DIR%gradlew.bat" (
  set "GRADLE_MODE=wrapper_bat"
  goto :eof
)
where gradle >nul 2>&1
if not errorlevel 1 (
  set "GRADLE_MODE=system_gradle"
  goto :eof
)
echo [ERROR] No Gradle runner found.
echo         Expected one of:
echo         - gradlew.bat in repo root
echo         - gradle in PATH
exit /b 1

:runGradle
if /i "%GRADLE_MODE%"=="wrapper_bat" (
  call "%SCRIPT_DIR%gradlew.bat" %*
  exit /b %errorlevel%
)
if /i "%GRADLE_MODE%"=="system_gradle" (
  gradle %*
  exit /b %errorlevel%
)
echo [ERROR] Unknown Gradle mode: %GRADLE_MODE%
exit /b 1

:resolveAdb
set "ADB_BIN="

if defined ADB if exist "%ADB%" set "ADB_BIN=%ADB%"

if not defined ADB_BIN (
  for /f "delims=" %%I in ('where adb.exe 2^>nul') do (
    if not defined ADB_BIN set "ADB_BIN=%%I"
  )
)
if not defined ADB_BIN (
  for /f "delims=" %%I in ('where adb 2^>nul') do (
    if not defined ADB_BIN set "ADB_BIN=%%I"
  )
)

if not defined ADB_BIN if defined ANDROID_SDK_ROOT if exist "%ANDROID_SDK_ROOT%\platform-tools\adb.exe" set "ADB_BIN=%ANDROID_SDK_ROOT%\platform-tools\adb.exe"
if not defined ADB_BIN if defined ANDROID_HOME if exist "%ANDROID_HOME%\platform-tools\adb.exe" set "ADB_BIN=%ANDROID_HOME%\platform-tools\adb.exe"
if not defined ADB_BIN if defined LOCALAPPDATA if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" set "ADB_BIN=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not defined ADB_BIN if exist "%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe" set "ADB_BIN=%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"

if not defined ADB_BIN (
  echo [ERROR] adb not found.
  echo         Set ADB, ANDROID_SDK_ROOT or ANDROID_HOME, or add adb to PATH.
  exit /b 1
)
exit /b 0

:resolveApkPath
set "APK_PATH=%BUILD_ROOT%\app\outputs\apk\debug\app-debug.apk"
if exist "%APK_PATH%" goto :eof

set "APK_PATH=%SCRIPT_DIR%app\build\outputs\apk\debug\app-debug.apk"
if exist "%APK_PATH%" goto :eof

if defined MASSDROID_BUILD_DIR (
  set "APK_PATH=%MASSDROID_BUILD_DIR%\app\outputs\apk\debug\app-debug.apk"
  if exist "%APK_PATH%" goto :eof
)

set "APK_PATH=%BUILD_ROOT%\app\outputs\apk\debug\app-debug.apk"
exit /b 0

:usage
echo Usage: build-install.bat [--build-root PATH]
echo        build-install.bat [-b PATH]
exit /b 0

:usage_error
echo Usage: build-install.bat [--build-root PATH]
echo        build-install.bat [-b PATH]
exit /b 1

:fail
popd >nul 2>&1
exit /b 1
