@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0build-fabric-1.21.ps1" %*
exit /b %ERRORLEVEL%
