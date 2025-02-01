#!/bin/bash

# Handle prmovies.host requests
# 1. Get iframe from the movie page
# 2. Load m3u8 playlist from iframe.
# 3. Get ts files from the iframe.

headers_source=(
  "Accept: */*"
  "Accept-Language: en-US,en;q=0.9"
  "Cache-Control: no-cache"
  "Connection: keep-alive"
  "Origin: https://embdproxy.xyz"
  "Pragma: no-cache"
  "Referer: https://embdproxy.xyz/"
  "Sec-Fetch-Dest: empty"
  "Sec-Fetch-Mode: cors"
  "Sec-Fetch-Site: cross-site"
  "User-Agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
  "sec-ch-ua: \"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\""
  "sec-ch-ua-mobile: ?0"
  "sec-ch-ua-platform: \"Linux\""
)

CURL_HEADERS=""
for header in "${headers_source[@]}"; do
    CURL_HEADERS+=" -H '$header;'"
done

# Get iframe url.
# The script will get one of the quality video. For this moment we can't decide which quality to choose.
prmovies_get_iframe_url() {
    echo $1 |
    wget -O- -i- --no-verbose --quiet | 
    hxnormalize -x |
    sed -n '/<div id="tab1"/,/<\/div>/ { s/.*src="\([^"]*\)".*/\1/p }'
}

# Return the list of playlists. Playlist should have links to qualities playlist
# Input params is the url to iframe.
prmovies_get_main_playlist_in_iframe() {
    curl "$1" \
  -H 'accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7' \
  -H 'accept-language: en-US,en;q=0.9' \
  -H 'cache-control: no-cache' \
  -H 'pragma: no-cache' \
  -H 'priority: u=0, i' \
  -H 'referer: https://prmovies.host/' \
  -H 'sec-ch-ua: "Not A(Brand";v="8", "Chromium";v="132"' \
  -H 'sec-ch-ua-mobile: ?0' \
  -H 'sec-ch-ua-platform: "Linux"' \
  -H 'sec-fetch-dest: iframe' \
  -H 'sec-fetch-mode: navigate' \
  -H 'sec-fetch-site: cross-site' \
  -H 'upgrade-insecure-requests: 1' \
  -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36' |
    hxnormalize -x |
    sed -n 's/.*sources: \[{file:"\([^"]*\)".*/\1/p'
}

# Get playlist link from iframe.
# prmovies_get_main_playlist_in_iframe() {
#     echo $1 |
#     sed  's/https://' | sed 's/\/\//https:\/\//' | 
#     wget -O- -i- --no-verbose --quiet | 
#     grep -E -o 'file:"(.*)m3u8' | 
#     sed -n 's/file:"//p'
# } 

# Return first link from m3u8 file with the lowest quality.
prmovies_get_quality_playlist() {
    local url="$1"
    local cmd="curl ${CURL_HEADERS} '${url}'"
    # eval "$cmd" | hxnormalize -x | sed -n '/https:/ {p;q}'
    eval "$cmd" | sed -n '/https:/ {p;q}'

    # curl "$CURL_HEADERS" "$1" |
    # sed -n '/https:/ {p;q}'
} 

# Create filename from playlist url.
# exmaple url https://prmovies.host/some-movie-name-Watch-online-full-movie/
prmovies_get_filename_from_url() {
    echo $1 |
    sed 's/.*\.host\/\([^/]*\)\/.*/\1/' # get
}

# Get m3u8 movie playlist with the segments.
prmovies_get_movie_playlist() {
    local url="$1"
    local cmd="curl ${CURL_HEADERS} '${url}'"
    eval "$cmd"
}

init_segments_lists() {
    # Get iframes url.
    IFRAME_URL=$(prmovies_get_iframe_url $URL)

    if [ -z "$IFRAME_URL" ]; then
        echo "Iframe url was not found."
        exit
    fi

    debug_log "Iframe URL: $IFRAME_URL"

    PLAYER_PLAYLIST_URL=$(prmovies_get_main_playlist_in_iframe $IFRAME_URL)

    if [ -z "$PLAYER_PLAYLIST_URL" ]; then
        echo "No iframes for player found. exit"
        exit
    fi

    debug_log "Playlist URL: $PLAYER_PLAYLIST_URL"
   
    # At this point we should have m3u8 playlist link where we can get quality playlist link.
    QUALITY_PLAYLIST_URL=$(prmovies_get_quality_playlist $PLAYER_PLAYLIST_URL)

    if [ -z "$QUALITY_PLAYLIST_URL" ]; then
        echo "Quality playlist was not found. exit"
        exit
    fi

    debug_log "Quality playlist URL: $QUALITY_PLAYLIST_URL"

    MOVIE_PLAYLIST=$(prmovies_get_movie_playlist $QUALITY_PLAYLIST_URL)

    if [ -z "$MOVIE_PLAYLIST" ]; then
        echo "Movie Playlist is empty. exit."
        exit
    fi

    # debug_log "Movie playlist URL: $MOVIE_PLAYLIST"
    
    MOVIENAME=$(prmovies_get_filename_from_url $URL)
    FILENAME="$MOVIENAME.mp4"

    # Save movie playlist data to use it in segments create.
    echo "$MOVIE_PLAYLIST" > $DIR_TMP/$MOVIENAME.m3u8
    # Since streams are encoded we should get and save the key.
    MOVIE_KEY_URI=$(echo "$MOVIE_PLAYLIST" | sed -n 's/.*URI="\([^"]*\)".*/\1/p')
    # Download and save.
    FILE_KEY="$VARS_DIR/$MOVIENAME.key"
    MOVIE_KEY=$(curl_request "$MOVIE_KEY_URI" "$CURL_HEADERS")
    echo "$MOVIE_KEY" > $FILE_KEY

    [ -d $OUTPUT ] || mkdir -p $OUTPUT

    debug_log "Filename: $FILENAME"
    # change the segments
    PARSE_SEGMENTS="seg-"
    
    
    # exit
        
    if [ "$DRY_RUN" == "0" ] 
    then
        if [ "$USE_FFMPEG_DOWNLOADER" == "1" ]; then
            # ffmpeg will not work here because of request data.
            # ffmpeg -i $QUALITY_PLAYLIST_URL -c copy -bsf:a aac_adtstoasc "$OUTPUT$FILENAME" -hide_banner -y
            echo "FFMPEG is not available"
        else
            # Create headers file for curl operations.
            FILE_HEADERS="$VARS_DIR/$MOVIENAME.headers"
            if test -f "$FILE_HEADERS"; then
                rm $FILE_HEADERS
            fi
            echo "$CURL_HEADERS" > "$VARS_DIR/$MOVIENAME.headers"
            local file_path=$(realpath "$DIR_TMP/$MOVIENAME.m3u8")
            segments_create file://$file_path $MOVIENAME 1
        fi
    fi

}
