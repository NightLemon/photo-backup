@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-standalone.ps1" %*
if errorlevel 1 (
  echo.
  echo Standalone server stopped with an error.
  pause
)