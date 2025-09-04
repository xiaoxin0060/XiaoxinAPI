# ==========================================================
# Xiaoxin API Platform - Dynamic Proxy Test Script  
# Function: Test gateway dynamic proxy calls to real external APIs
# Usage: .\api_test.ps1
# ==========================================================

param(
    [string]$GatewayUrl = "http://localhost:9999",
    [string]$AccessKey = "xiaoxinAccessKey",
    [string]$SecretKey = "xiaoxinSecretKey"
)

# UTF-8 encoding is now configured globally in PowerShell profile
# UTF-8编码现已在PowerShell配置文件中全局配置

Write-Host "Xiaoxin API Platform - Dynamic Proxy Test" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
Write-Host ""

# SHA256 signature calculation function
function Calculate-SHA256Sign {
    param([string]$body, [string]$secretKey)
    
    $content = $body + "." + $secretKey
    $hasher = [System.Security.Cryptography.SHA256]::Create()
    $hashBytes = $hasher.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($content))
    $hashString = [System.BitConverter]::ToString($hashBytes) -replace '-'
    return $hashString.ToLower()
}

# Service connection check function
function Test-Service {
    param([string]$url, [string]$name)
    
    try {
        # Any HTTP response (200, 404, 403, etc.) means service is up
        $response = Invoke-WebRequest -Uri $url -Method GET -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
        Write-Host "[OK] $name is running normally (HTTP $($response.StatusCode))" -ForegroundColor Green
        return $true
    }
    catch {
        if ($_.Exception.Response) {
            # Got an HTTP error response, which means service is responding
            $statusCode = $_.Exception.Response.StatusCode
            Write-Host "[OK] $name is running normally (HTTP $statusCode)" -ForegroundColor Green
            return $true
        } else {
            # Network/connection error
            Write-Host "[ERROR] $name connection failed" -ForegroundColor Red
            return $false
        }
    }
}

# API interface test function
function Test-API {
    param([string]$path, [string]$name)
    
    Write-Host ""
    Write-Host "Testing API: $name" -ForegroundColor Yellow
    Write-Host "Request Path: $path" -ForegroundColor Gray
    
    # Generate authentication parameters
    $timestamp = [int][double]::Parse((Get-Date -UFormat %s))
    $nonce = Get-Random -Maximum 10000
    $body = ""
    $sign = Calculate-SHA256Sign -body $body -secretKey $SecretKey
    
    Write-Host "Auth Params: accessKey=$AccessKey, timestamp=$timestamp, nonce=$nonce" -ForegroundColor Gray
    
    $headers = @{
        'accessKey' = $AccessKey
        'timestamp' = $timestamp.ToString()
        'nonce' = $nonce.ToString()
        'sign' = $sign
        'body' = $body
        'Content-Type' = 'application/json'
    }
    
    try {
        Write-Host "Sending request..." -ForegroundColor Cyan
        $response = Invoke-RestMethod -Uri "$GatewayUrl$path" -Headers $headers -TimeoutSec 15
        
        Write-Host "[SUCCESS] API call succeeded!" -ForegroundColor Green
        Write-Host "Response Content:" -ForegroundColor Cyan
        $response | ConvertTo-Json -Depth 5 | Write-Host -ForegroundColor White
        
        # Check response status
        if ($response.code -eq 200) {
            Write-Host "[OK] Interface returned success status code: 200" -ForegroundColor Green
        } else {
            Write-Host "[WARNING] Interface returned status code: $($response.code)" -ForegroundColor Yellow
        }
        
    } catch {
        Write-Host "[FAILED] API call failed" -ForegroundColor Red
        Write-Host "Error Message: $($_.Exception.Message)" -ForegroundColor Red
        
        if ($_.Exception.Response) {
            $statusCode = $_.Exception.Response.StatusCode
            Write-Host "HTTP Status Code: $statusCode" -ForegroundColor Red
            
            # Provide suggestions based on status code
            switch ($statusCode) {
                "Forbidden" { Write-Host "Suggestion: Check if accessKey/secretKey is correct, or user has permission" -ForegroundColor Yellow }
                "NotFound" { Write-Host "Suggestion: Check if API path is correct, or interface is configured in database" -ForegroundColor Yellow }
                "InternalServerError" { Write-Host "Suggestion: Check if target API is available, check gateway logs for details" -ForegroundColor Yellow }
                default { Write-Host "Suggestion: Check network connection and service status" -ForegroundColor Yellow }
            }
        }
    }
    Write-Host ("-" * 60) -ForegroundColor Gray
}

# Main program starts
Write-Host "Test Configuration:" -ForegroundColor Cyan
Write-Host "  Gateway URL: $GatewayUrl"
Write-Host "  Access Key: $AccessKey"
Write-Host "  Current Time: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host ""

# Check service status
Write-Host "Checking service status..." -ForegroundColor Cyan
$mainService = Test-Service "http://localhost:8101" "Main Service (xiaoxinapi:8101)"
$gatewayService = Test-Service "$GatewayUrl" "Gateway Service (xiaoxinapi-gateway:9999)"

if (-not $gatewayService) {
    Write-Host ""
    Write-Host "[ERROR] Gateway service is not running!" -ForegroundColor Red
    Write-Host "Please follow these steps first:" -ForegroundColor Yellow
    Write-Host "1. Start MySQL, Redis, Nacos and other dependent services" -ForegroundColor Yellow
    Write-Host "2. In xiaoxinapi directory run: mvn spring-boot:run" -ForegroundColor Yellow
    Write-Host "3. In xiaoxinapi-gateway directory run: mvn spring-boot:run" -ForegroundColor Yellow
    Write-Host ""
    Read-Host "Press Enter to exit"
    exit 1
}

Write-Host ""
Write-Host "Starting API interface tests..." -ForegroundColor Green
Write-Host "===============================" -ForegroundColor Green

# Execute interface tests (according to paths configured in database)
Test-API "/api/geo/query" "IP Geographic Location Query"
Test-API "/api/sample/random" "HTTPBin JSON Test Data"
Test-API "/api/data/test" "HTTPBin UUID Generator"

# Test summary
Write-Host ""
Write-Host "Test Summary:" -ForegroundColor Green
Write-Host "=============" -ForegroundColor Green

if ($mainService -and $gatewayService) {
    Write-Host "[SUCCESS] All services are running normally" -ForegroundColor Green
    Write-Host ""
    Write-Host "If you see '[SUCCESS] API call succeeded!' with real external API data," -ForegroundColor Green
    Write-Host "it means the dynamic proxy function is working properly!" -ForegroundColor Green
} else {
    Write-Host "[WARNING] Some services are not running normally" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Troubleshooting Guide:" -ForegroundColor Cyan
Write-Host "- If 403 error occurs: Check database user and interface configuration" -ForegroundColor Cyan
Write-Host "- If 500 error occurs: Check if external APIs are accessible" -ForegroundColor Cyan
Write-Host "- If connection error occurs: Check if services are started and ports are correct" -ForegroundColor Cyan
Write-Host ""
Write-Host "View detailed logs:" -ForegroundColor Cyan
Write-Host "- Gateway logs: xiaoxinapi-gateway console output" -ForegroundColor Cyan
Write-Host "- Main service logs: xiaoxinapi console output" -ForegroundColor Cyan

Write-Host ""
Write-Host "Test completed!" -ForegroundColor Green
Write-Host ""
Read-Host "Press Enter to exit"