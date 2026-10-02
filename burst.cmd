@echo off
rem One-command burst test (Windows).   Usage: burst.cmd <BASE_URL>
rem Needs only a JDK 21. Tunables via environment: ADMIN_KEY USERS REQUESTS SEATS HOT STORM_USERS CONCURRENCY
setlocal
set "BASE_URL=%~1"
if "%BASE_URL%"=="" set "BASE_URL=http://localhost:8080"
java "%~dp0burst\Burst.java" %BASE_URL%
exit /b %ERRORLEVEL%
