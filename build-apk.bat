@echo off
rem WebView APK Builder. Double-click to open the builder window.
rem With app names it builds in this terminal instead: build-apk.bat google my-shop   (or: build-apk.bat all)
title WebView APK Builder
setlocal
cd /d "%~dp0"

set "PY="
where py >nul 2>nul && set "PY=py -3"
if not defined PY where python >nul 2>nul && set "PY=python"
if not defined PY (
    echo ERROR: Python not found. Install Python 3 from https://www.python.org/
    pause
    exit /b 1
)

rem App names given: terminal mode.
if not "%~1"=="" goto terminal

rem The window needs pywebview; install it once if it is missing.
%PY% -c "import webview" >nul 2>nul
if errorlevel 1 (
    echo Installing the window component ^(pywebview^), one time only...
    %PY% -m pip install --user -r tool\requirements.txt
    %PY% -c "import webview" >nul 2>nul || goto terminal
)

rem pythonw/pyw runs without a console window, so this one can close.
set "PYW=pythonw"
where pyw >nul 2>nul && set "PYW=pyw -3"
start "" %PYW% tool\gui.py
exit /b 0

:terminal
%PY% tool\build_apk.py %*
set "RC=%ERRORLEVEL%"
echo.
pause
exit /b %RC%
