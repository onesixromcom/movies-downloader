#!/usr/local/env bash

# Colors 
CRed='\033[0;31m'
CGreen='\033[0;32m'
CBlue='\033[0;34m'
CPurple='\033[0;35m'
CN='\033[0m' # No Color

# Get host from URL.
get_host() {
    echo $1 |
    awk -F[/:] '{print $4}'
}

# Extract domain from URL
extract_domain() {
    local url="$1"
    # Remove protocol (http://, https://, etc.)
    local domain=$(echo "$url" | sed -E 's#^(https?:)?//([^/]+).*#\2#')
    # Remove port number if present
    domain=$(echo "$domain" | sed -E 's#(.+):[0-9]+$#\1#')
    echo "$domain"
}
