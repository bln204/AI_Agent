# AI Agent Application - Build and Run Script
# This script builds and runs the Spring Boot application

Write-Host "`n" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  AI Agent Web Application - Build Script" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "`n" -ForegroundColor Green

# Check Java availability
Write-Host "[*] Checking Java installation..." -ForegroundColor Yellow
$javaVersion = java -version 2>&1 | Select-Object -First 1
Write-Host "    $javaVersion" -ForegroundColor Green

# Check if Maven is available
Write-Host "`n[*] Checking Maven installation..." -ForegroundColor Yellow
try {
    $mvnVersion = mvn --version 2>&1 | Select-Object -First 1
    Write-Host "    $mvnVersion" -ForegroundColor Green
    $mavenAvailable = $true
} catch {
    Write-Host "    Maven not found in PATH" -ForegroundColor Red
    Write-Host "    Trying alternative Maven locations..." -ForegroundColor Yellow
    $mavenAvailable = $false
}

# Try alternative Maven paths
$alternateMavenPaths = @(
    "C:\Program Files\Apache\Maven\bin\mvn.cmd",
    "C:\Program Files (x86)\Apache\Maven\bin\mvn.cmd",
    "C:\Maven\bin\mvn.cmd"
)

foreach ($mavenPath in $alternateMavenPaths) {
    if (Test-Path $mavenPath) {
        Write-Host "    Found Maven at: $mavenPath" -ForegroundColor Green
        $mvn = $mavenPath
        $mavenAvailable = $true
        break
    }
}

# Build instructions
Write-Host "`n[!] BUILD AND RUN INSTRUCTIONS" -ForegroundColor Cyan
Write-Host "======================================" -ForegroundColor Cyan

if ($mavenAvailable) {
    Write-Host "`n[✓] Maven is available. Running build..." -ForegroundColor Green
    Write-Host "`nExecuting: mvn clean package -DskipTests" -ForegroundColor Yellow
    Write-Host "`n"
    
    # Run Maven build
    cd "d:\AI_Agent"
    mvn clean package -DskipTests
    
    if ($LASTEXITCODE -eq 0) {
        Write-Host "`n[✓] Build successful!" -ForegroundColor Green
        Write-Host "`nTo run the application, execute:" -ForegroundColor Cyan
        Write-Host "   mvn spring-boot:run" -ForegroundColor Yellow
        Write-Host "`nOr run the JAR file:" -ForegroundColor Cyan
        Write-Host "   java -jar target/ai-agent-web-1.0.0.jar" -ForegroundColor Yellow
    } else {
        Write-Host "`n[✗] Build failed!" -ForegroundColor Red
    }
} else {
    Write-Host "`n[✗] Maven not found in system PATH" -ForegroundColor Red
    Write-Host "`nPlease install Maven or use one of these alternatives:" -ForegroundColor Yellow
    Write-Host "`n1. Use VS Code with Spring Boot Extension" -ForegroundColor Cyan
    Write-Host "2. Install Maven from: https://maven.apache.org/download.cgi" -ForegroundColor Cyan
    Write-Host "3. Use Gradle instead:" -ForegroundColor Cyan
    Write-Host "`n   gradle build" -ForegroundColor Yellow
}

Write-Host "`n[*] Database Configuration:" -ForegroundColor Cyan
Write-Host "    Driver: MySQL" -ForegroundColor Green
Write-Host "    URL: jdbc:mysql://localhost:3306/ai_agent" -ForegroundColor Green
Write-Host "    Username: root" -ForegroundColor Green
Write-Host "    Password: 1234" -ForegroundColor Green
Write-Host "`n    Ensure MySQL is running before starting the application!" -ForegroundColor Yellow

Write-Host "`n[*] Application Details:" -ForegroundColor Cyan
Write-Host "    Port: 8080" -ForegroundColor Green
Write-Host "    Context Path: /api" -ForegroundColor Green
Write-Host "    Login URL: http://localhost:8080/api/login" -ForegroundColor Green
Write-Host "    Dashboard URL: http://localhost:8080/api/dashboard" -ForegroundColor Green

Write-Host "`n[*] API Endpoints:" -ForegroundColor Cyan
Write-Host "    POST /api/auth/login" -ForegroundColor Yellow
Write-Host "    POST /api/auth/google-login" -ForegroundColor Yellow
Write-Host "    GET  /api/agents" -ForegroundColor Yellow
Write-Host "    POST /api/agents" -ForegroundColor Yellow
Write-Host "    GET  /api/agents/{id}" -ForegroundColor Yellow
Write-Host "    PUT  /api/agents/{id}" -ForegroundColor Yellow
Write-Host "    DELETE /api/agents/{id}" -ForegroundColor Yellow

Write-Host "`n[*] Documentation:" -ForegroundColor Cyan
Write-Host "    See AUTHENTICATION.md for detailed authentication documentation" -ForegroundColor Green

Write-Host "`n============================================" -ForegroundColor Cyan
Write-Host "`n"
