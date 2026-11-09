@echo off
cd /d "%~dp0"
where py >nul 2>nul
if %errorlevel%==0 (
    py -3 candlelab.py
) else (
    python candlelab.py
)
if errorlevel 1 pause
