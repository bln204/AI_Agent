@echo off
cd /d "%~dp0"
setlocal enabledelayedexpansion

echo.
echo ========================================
echo  Building AI Agent Application
echo ========================================
echo.

if exist "target\classes" (
    echo Cleaning old build...
    rmdir /s /q "target\classes" >nul 2>&1
)

echo Compiling source files...
set JAVA_HOME=C:\Program Files\Java\jdk-22
set MAVEN_HOME=C:\Program Files\Apache\Maven

REM Try to compile with javac directly
echo.
echo Note: Please ensure Maven is installed or use the Spring Boot IDE integration.
echo To build and run this project, execute one of:
echo.
echo   mvn clean package
echo   mvn spring-boot:run
echo.
echo OR use VS Code with Spring Boot Extension
echo.

pause
