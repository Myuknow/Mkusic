@echo off
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Please install Node.js 22 or later.
  pause
  exit /b 1
)
if not exist .env node scripts\setup.js
node server.js
pause
