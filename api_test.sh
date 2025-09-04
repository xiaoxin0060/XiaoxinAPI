#!/bin/bash
# ==========================================================
# Xiaoxin API Platform - Dynamic Proxy Test Script
# Function: Test gateway dynamic proxy calls to real external APIs
# Usage: chmod +x api_test.sh && ./api_test.sh
# Requirements: curl, openssl, jq (for JSON formatting)
# ==========================================================

# Default configuration
GATEWAY_URL="${1:-http://localhost:9999}"
ACCESS_KEY="${2:-xiaoxinAccessKey}"
SECRET_KEY="${3:-xiaoxinSecretKey}"

# Color definitions
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
WHITE='\033[0;37m'
GRAY='\033[0;90m'
NC='\033[0m' # No Color

# Print colored output
print_color() {
    local color=$1
    shift
    echo -e "${color}$*${NC}"
}

# SHA256 signature calculation function
calculate_sha256_sign() {
    local body="$1"
    local secret_key="$2"
    local content="${body}.${secret_key}"
    echo -n "$content" | openssl dgst -sha256 | sed 's/^.* //'
}

# Service connection check function
test_service() {
    local url="$1"
    local name="$2"
    
    # Try to connect to service (any HTTP response means service is up)
    local status_code=$(curl -s -o /dev/null -w "%{http_code}" --connect-timeout 3 --max-time 5 "$url" 2>/dev/null)
    
    # Any HTTP status code (200, 404, 403, etc.) means the service is responding
    if [ -n "$status_code" ] && [ "$status_code" != "000" ]; then
        print_color $GREEN "[OK] $name is running normally (HTTP $status_code)"
        return 0
    else
        print_color $RED "[ERROR] $name connection failed"
        return 1
    fi
}

# API interface test function
test_api() {
    local path="$1"
    local name="$2"
    
    echo ""
    print_color $YELLOW "Testing API: $name"
    print_color $GRAY "Request Path: $path"
    
    # Generate authentication parameters
    local timestamp=$(date +%s)
    local nonce=$((RANDOM % 10000))
    local body=""
    local sign=$(calculate_sha256_sign "$body" "$SECRET_KEY")
    
    print_color $GRAY "Auth Params: accessKey=$ACCESS_KEY, timestamp=$timestamp, nonce=$nonce"
    
    print_color $CYAN "Sending request..."
    
    # Make HTTP request
    local response=$(curl -s -X GET "${GATEWAY_URL}${path}" \
        -H "accessKey: $ACCESS_KEY" \
        -H "timestamp: $timestamp" \
        -H "nonce: $nonce" \
        -H "sign: $sign" \
        -H "body: $body" \
        -H "Content-Type: application/json" \
        -w "HTTPSTATUS:%{http_code}" \
        --connect-timeout 15 \
        --max-time 15)
    
    # Extract HTTP status code
    local http_status=$(echo "$response" | grep -o "HTTPSTATUS:[0-9]*" | sed 's/HTTPSTATUS://')
    local response_body=$(echo "$response" | sed 's/HTTPSTATUS:[0-9]*$//')
    
    if [ "$http_status" -eq 200 ] 2>/dev/null; then
        print_color $GREEN "[SUCCESS] API call succeeded!"
        print_color $CYAN "Response Content:"
        
        # Try to format JSON if jq is available
        if command -v jq >/dev/null 2>&1; then
            echo "$response_body" | jq . 2>/dev/null || echo "$response_body"
        else
            echo "$response_body"
        fi
        
        # Check response status code in JSON
        local json_code=$(echo "$response_body" | grep -o '"code":[0-9]*' | sed 's/"code"://')
        if [ "$json_code" = "200" ]; then
            print_color $GREEN "[OK] Interface returned success status code: 200"
        else
            print_color $YELLOW "[WARNING] Interface returned status code: $json_code"
        fi
        
    else
        print_color $RED "[FAILED] API call failed"
        print_color $RED "HTTP Status Code: $http_status"
        print_color $RED "Response Body: $response_body"
        
        # Provide suggestions based on status code
        case $http_status in
            403)
                print_color $YELLOW "Suggestion: Check if accessKey/secretKey is correct, or user has permission"
                ;;
            404)
                print_color $YELLOW "Suggestion: Check if API path is correct, or interface is configured in database"
                ;;
            500)
                print_color $YELLOW "Suggestion: Check if target API is available, check gateway logs for details"
                ;;
            *)
                print_color $YELLOW "Suggestion: Check network connection and service status"
                ;;
        esac
    fi
    print_color $GRAY "------------------------------------------------------------"
}

# Check dependencies
check_dependencies() {
    local missing_deps=()
    
    if ! command -v curl >/dev/null 2>&1; then
        missing_deps+=("curl")
    fi
    
    if ! command -v openssl >/dev/null 2>&1; then
        missing_deps+=("openssl")
    fi
    
    if [ ${#missing_deps[@]} -ne 0 ]; then
        print_color $RED "Missing required dependencies: ${missing_deps[*]}"
        echo ""
        print_color $YELLOW "Please install them:"
        print_color $YELLOW "  Ubuntu/Debian: sudo apt-get install ${missing_deps[*]}"
        print_color $YELLOW "  CentOS/RHEL: sudo yum install ${missing_deps[*]}"
        print_color $YELLOW "  macOS: brew install ${missing_deps[*]}"
        echo ""
        exit 1
    fi
    
    if ! command -v jq >/dev/null 2>&1; then
        print_color $YELLOW "[WARNING] jq not found - JSON output will not be formatted"
        print_color $YELLOW "Install jq for better JSON display: sudo apt-get install jq (or brew install jq)"
        echo ""
    fi
}

# Main program starts
print_color $GREEN "Xiaoxin API Platform - Dynamic Proxy Test"
print_color $GREEN "========================================="
echo ""

print_color $CYAN "Test Configuration:"
echo "  Gateway URL: $GATEWAY_URL"
echo "  Access Key: $ACCESS_KEY"
echo "  Current Time: $(date '+%Y-%m-%d %H:%M:%S')"
echo ""

# Check dependencies
check_dependencies

# Check service status
print_color $CYAN "Checking service status..."
main_service_ok=0
gateway_service_ok=0

if test_service "http://localhost:8101" "Main Service (xiaoxinapi:8101)"; then
    main_service_ok=1
fi

if test_service "$GATEWAY_URL" "Gateway Service (xiaoxinapi-gateway:9999)"; then
    gateway_service_ok=1
fi

if [ $gateway_service_ok -eq 0 ]; then
    echo ""
    print_color $RED "[ERROR] Gateway service is not running!"
    print_color $YELLOW "Please follow these steps first:"
    print_color $YELLOW "1. Start MySQL, Redis, Nacos and other dependent services"
    print_color $YELLOW "2. In xiaoxinapi directory run: mvn spring-boot:run"
    print_color $YELLOW "3. In xiaoxinapi-gateway directory run: mvn spring-boot:run"
    echo ""
    print_color $YELLOW "Press Enter to exit"
    read
    exit 1
fi

echo ""
print_color $GREEN "Starting API interface tests..."
print_color $GREEN "==============================="

# Execute interface tests (according to paths configured in database)
test_api "/api/geo/query" "IP Geographic Location Query"
test_api "/api/sample/random" "HTTPBin JSON Test Data"  
test_api "/api/data/test" "HTTPBin UUID Generator"

# Test summary
echo ""
print_color $GREEN "Test Summary:"
print_color $GREEN "============="

if [ $main_service_ok -eq 1 ] && [ $gateway_service_ok -eq 1 ]; then
    print_color $GREEN "[SUCCESS] All services are running normally"
    echo ""
    print_color $GREEN "If you see '[SUCCESS] API call succeeded!' with real external API data,"
    print_color $GREEN "it means the dynamic proxy function is working properly!"
else
    print_color $YELLOW "[WARNING] Some services are not running normally"
fi

echo ""
print_color $CYAN "Troubleshooting Guide:"
print_color $CYAN "- If 403 error occurs: Check database user and interface configuration"
print_color $CYAN "- If 500 error occurs: Check if external APIs are accessible"  
print_color $CYAN "- If connection error occurs: Check if services are started and ports are correct"
echo ""
print_color $CYAN "View detailed logs:"
print_color $CYAN "- Gateway logs: xiaoxinapi-gateway console output"
print_color $CYAN "- Main service logs: xiaoxinapi console output"

echo ""
print_color $GREEN "Test completed!"
echo ""
print_color $YELLOW "Press Enter to exit"
read
