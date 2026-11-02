#!/usr/bin/env bash

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

# Resolve URI from playlist against the playlist url.
# $1 - base url, $2 - absolute, root relative or relative (../xxx/index.m3u8) uri.
url_resolve() {
    local base="$1" ref="$2"
    local origin=$(echo "$base" | sed -E 's#^(https?://[^/]+).*#\1#')
    case "$ref" in
        http://*|https://*) echo "$ref"; return ;;
        //*) echo "${base%%:*}:$ref"; return ;;
        /*) echo "$origin$ref"; return ;;
    esac
    # Directory of the base url without query.
    local path="${base#$origin}"
    path="${path%%\?*}"
    path="${path%/*}"
    local segs parts=() seg
    IFS=/ read -ra segs <<< "$path/$ref"
    for seg in "${segs[@]}"; do
        case "$seg" in
            ""|.) ;;
            ..) [ ${#parts[@]} -gt 0 ] && unset 'parts[-1]' ;;
            *) parts+=("$seg") ;;
        esac
    done
    local IFS=/
    echo "$origin/${parts[*]}"
}

# Get variant uri from master m3u8 by #EXT-X-STREAM-INF RESOLUTION tag.
# Returns the highest variant with height <= $2.
# $1 - playlist file, $2 - max height (quality).
m3u8_variant_by_resolution() {
    awk -v max="$2" '
        { sub(/\r$/, "") }
        /^#EXT-X-STREAM-INF/ {
            h = 0
            if (match($0, /RESOLUTION=[0-9]+x[0-9]+/)) {
                split(substr($0, RSTART + 11, RLENGTH - 11), r, "x")
                h = r[2] + 0
            }
            next
        }
        h && !/^#/ && NF {
            if (h <= max && h > best) { best = h; uri = $0 }
            h = 0
        }
        END { if (uri != "") print uri }
    ' "$1"
}
