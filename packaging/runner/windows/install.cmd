@echo off
chcp 65001 >nul
"%~dp0runtime\bin\java.exe" -jar "%~dp0runner-setup.jar" --cli %*
exit /b %errorlevel%
