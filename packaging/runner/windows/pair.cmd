@echo off
set /p MANAGER_URL=Manager HTTPS URL:
"%~dp0runtime\bin\java.exe" -jar "%~dp0server-manager.jar" --runner-pair "--manager-url=%MANAGER_URL%"
pause
