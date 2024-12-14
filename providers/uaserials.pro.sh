#!/bin/bash

# New version of the websites uses now js object format and PlayerJS to handle all data.
# 1. First we need to get iframe url.
# 2. extract json data from the page
# 3. get all playlist files with required quality 

# Get iframe url.
uaserials_get_iframe_url() {
    echo $1 |
    wget -O- -i- --no-verbose --quiet | 
    hxnormalize -x |
    sed -n "s/.*data-src=\"\([^']\+\)embed\(.*\)\"/\1embed\2/p"
}

# Return the list of playlists. Playlist should have links to qualities playlist
# Input params is the url to iframe.
uaserials_get_main_playlists() {
    echo $1 |
    wget -O- -i- --no-verbose --quiet | 
    hxnormalize -x |
    sed -n '/new Playerjs({/,/});/p' | 
    sed -e 's/.*new Playerjs(\({.*}\)).*/\1/' > $DIR_TMP/player.js
    # Create concatenated js and process it.
    cat $DIR_SCRIPTS/uaserials_pro_begin.js $DIR_TMP/player.js $DIR_SCRIPTS/uaserials_pro_end.js > $DIR_TMP/uaserials_pro.js
    rm $DIR_TMP/player.js
    echo $(node $DIR_TMP/uaserials_pro.js "$SEASON")
}

# Get playlist link from iframe.
uaserials_get_main_playlist_in_iframe() {
    echo $1 |
    sed  's/https://' | sed 's/\/\//https:\/\//' | 
    wget -O- -i- --no-verbose --quiet | 
    grep -E -o 'file:"(.*)m3u8' | 
    sed -n 's/file:"//p'
} 

# Get quality playlist from main playlist.
uaserials_get_quality_playlist() {
    echo $1 |
    wget -O- -i- --no-verbose --quiet | 
    grep -E -o "https://(.*)hls\/$QUALITY\/(.*)m3u8"
} 

# Create filename from playlist url.
# exmaple url https://sparrow.tortuga.wtf/hls/serials/solar.opposites.s01e08.adrianzp.mvo_45026/hls/index.m3u8
uaserials_get_filename_from_url() {
    echo $1 |
    sed 's/\/hls\/index.*//' |  # remove text after hls/index
    sed 's#.*/##' # leave only last word
}

init_segments_lists() {
    # Get iframes url.
    IFRAME_URL=$(uaserials_get_iframe_url $URL)

    if [ -z "$IFRAME_URL" ]; then
        echo "Iframe url was not found."
        exit
    fi

    debug_log "Iframe URL: $IFRAME_URL"

    PLAYER_IFRAMES=$(uaserials_get_main_playlists $IFRAME_URL)

    # PLAYER_IFRAMES=$(node ./scripts/crypto.js $IFRAME_URL $SEASON $SOUND)

    if [ -z "$PLAYER_IFRAMES" ]; then
        echo "No iframes for player found. exit"
        exit
    fi
    debug_log $PLAYER_IFRAMES

    # Split strings to an array.
    PLAYER_IFRAMES=($(echo "$PLAYER_IFRAMES" | tr ',' '\n'))

    TOTAL_ITEMS=(${#PLAYER_IFRAMES[@]})
    echo "Total before skip: $TOTAL_ITEMS"

    # Skip videos from beginning.
    if [ $SKIP -gt 0 ]; then
        for (( i=0;i<$(($SKIP));i++)); do
            echo "skip ${PLAYER_IFRAMES[${i}]}"
            unset PLAYER_IFRAMES[$i]
        done
    fi
    
    
    # Unset video we dont want to download.
    if [ $TOTAL -gt 0 ]; then
        echo "Set total frames to: $TOTAL"
        for (( i = $(($SKIP)) + $(($TOTAL)); i < (($TOTAL_ITEMS)); i++ )); do
            unset PLAYER_IFRAMES[$i]
        done
    fi

    [ -d $OUTPUT ] || mkdir -p $OUTPUT

    for PLAYLIST_MAIN in "${PLAYER_IFRAMES[@]}";
    do
        #VIDEO_URI=$(uaserials_get_main_playlist_in_iframe $iframe)
        #echo "playlist main = $VIDEO_URI"
        PLAYLIST_QUALITY=$(uaserials_get_quality_playlist $PLAYLIST_MAIN)
        debug_log "Playlist quality = $PLAYLIST_QUALITY"
        if [ -z "$PLAYLIST_QUALITY" ]; then
            echo "Playlist for selected quality not found. Try another."
            exit
        fi
        
        MOVIENAME=$(uaserials_get_filename_from_url $PLAYLIST_MAIN)
        FILENAME="$MOVIENAME.mp4"
        debug_log "Filename: $FILENAME"
        
        if [ "$DRY_RUN" == "0" ] 
        then
            if [ "$USE_FFMPEG_DOWNLOADER" == "1" ]; then
                ffmpeg -i $PLAYLIST_QUALITY -c copy -bsf:a aac_adtstoasc "$OUTPUT$FILENAME" -hide_banner -y
            else
                segments_create $PLAYLIST_QUALITY $MOVIENAME 0
            fi
        fi
        echo "----------------------------------------------"
    done
    
}
