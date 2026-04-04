# Rebuild JAR from compiled classes
Write-Host "`n=== Rebuild JAR from compiled classes ===" -ForegroundColor Green

$targetDir = "D:\AI_Agent\target"
$classesDir = "$targetDir\classes"
$jarFile = "$targetDir\ai-agent-web-1.0.0.jar"

if(-not (Test-Path $classesDir)) {
    Write-Host "❌ Classes directory not found: $classesDir" -ForegroundColor Red
    exit 1
}

Write-Host "Classes directory: $classesDir" -ForegroundColor Cyan
Write-Host "Output JAR: $jarFile" -ForegroundColor Cyan

# Find Java 
$javaExe = (Get-Command java -ErrorAction SilentlyContinue).Source
if(-not $javaExe) {
    Write-Host "❌ Java not found in PATH" -ForegroundColor Red
    exit 1
}

$javaDir = Split-Path (Split-Path $javaExe)
Write-Host "Java location: $javaDir" -ForegroundColor Yellow

# Try to find jar.exe
$jarExe = "$javaDir\bin\jar.exe"
if(-not (Test-Path $jarExe)) {
    Write-Host "⚠️  jar.exe not found at: $jarExe" -ForegroundColor Yellow
    Write-Host "Will try creating minimal JAR with jar command..." -ForegroundColor Yellow
    
    # Try jar from PATH
    $jarExe = "jar"
}

Write-Host "Creating JAR file..." -ForegroundColor Cyan
Push-Location $targetDir

try {
    # Create JAR with manifest
    & $jarExe cvfe $jarFile "com.aiagent.AiAgentApplication" -C $classesDir . 2>&1 | Select-Object -Last 10
    
    if($LASTEXITCODE -eq 0) {
        Write-Host "`n✅ JAR created successfully" -ForegroundColor Green
        Write-Host "File: $jarFile" -ForegroundColor Yellow
        Get-Item $jarFile | Select-Object FullName, Length
    } else {
        Write-Host "`n❌ JAR creation failed with code: $LASTEXITCODE" -ForegroundColor Red
    }
} catch {
    Write-Host "❌ Error: $($_.Exception.Message)" -ForegroundColor Red
} finally {
    Pop-Location
}
