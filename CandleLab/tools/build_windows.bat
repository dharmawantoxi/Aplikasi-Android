@echo off
setlocal
cd /d "%~dp0\.."
py -3 -m pip install --upgrade pyinstaller==6.16.0
if errorlevel 1 exit /b 1
py -3 -m PyInstaller --noconfirm --clean --onefile --windowed --name CandleLab --paths . candlelab.py
if errorlevel 1 exit /b 1
echo.
echo CandleLab.exe tersedia di folder dist\CandleLab.exe
pause
