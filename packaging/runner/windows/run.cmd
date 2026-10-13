@echo off
set "RUNNER_PACKAGE_HOME=%~dp0"
"%~dp0runtime\bin\java.exe" -jar "%~dp0runner-bootstrap.jar"
