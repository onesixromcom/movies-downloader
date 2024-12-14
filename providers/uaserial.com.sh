#!/bin/bash

# Script is working only with --use-ffmpeg flag since playlist has no absolute urls to the videos.

uaserial_get_embed_list() {
    echo $1 |
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    # cat ./tmp/season-1.html | 
    hxnormalize -x | # normalize html
    hxselect -i "select[id=\"select-series\"]" | # select videos only from first player
    sed 's/value/href/g' | #replacements to make hxwls work
    sed 's/<option /<a /g' |  #replacements to make hxwls work
    hxwls
}

uaserial_get_service_iframe_url() {
    echo $1 |
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    hxnormalize -x | 
    hxselect -i "select[class=\"voices__select\"]" |
    sed 's/value/href/g' |
    sed 's/<option /<a /g' |
    hxwls
}

uaserial_get_main_playlist_in_iframe() {
    echo $1 |
    # sed  's/https://' | sed 's/\/\//https:\/\//' | 
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    grep -E -o 'file: "(.*)m3u8' | 
    sed -n 's/file: "//p'
}

# Example of url "https://calypso.tortuga.wtf/hls/serials/pokemon.s01e01.ntn.dvo_37789/hls/index.m3u8" |
uaserial_get_filename_from_url() {
    echo $1 |
    sed 's/\/hls\/index.*//' |  # remove text before hls
    sed 's#.*/##' # leave only last word
}

# Steps to get playlist with segments.
# 1. download main page
# 2. find current series list in the selected-series block
# 3. get iframe url
# 4. find playslist link in iframe

init_segments_lists() {
    # Get the list of embed urls.
    IFRAMES_LIST=($(uaserial_get_embed_list $URL))

    TOTAL_ITEMS=(${#IFRAMES_LIST[@]})
    debug_log "total before skip: $TOTAL_ITEMS"

    # Removing first skipped videos.
    if [ $SKIP -gt 0 ]; then
        for (( i=0;i<$(($SKIP));i++)); do
            unset IFRAMES_LIST[$i]
        done
    fi

    # Unset video we dont want to download.
    if [ $TOTAL -gt 0 ]; then
        echo "Set total frames to: $TOTAL"
        for (( i = $(($SKIP)) + $(($TOTAL)); i < (($TOTAL_ITEMS)); i++ )); do
            #echo "unset ${IFRAMES_LIST[${i}]}"
            unset IFRAMES_LIST[$i]
        done
    fi

    for iframe in "${IFRAMES_LIST[@]}";
    do
        # echo "IFRAME = $iframe"
        SERVICE_IFRAME=($(uaserial_get_service_iframe_url https://uaserial.com$iframe))
        # echo "SERVICE IFRAME = $SERVICE_IFRAME"
        PLAYLIST=$(uaserial_get_main_playlist_in_iframe $SERVICE_IFRAME)
        # echo "playlist main = $PLAYLIST"
        MOVIENAME=$(uaserial_get_filename_from_url $PLAYLIST)
        FILENAME="$MOVIENAME.mp4"
        debug_log "Filename to save $FILENAME";
        
        if [ "$DRY_RUN" == "0" ] 
        then
            if [ "$USE_FFMPEG_DOWNLOADER" == "1" ]; then
                ffmpeg -i $PLAYLIST -c copy -bsf:a aac_adtstoasc "$OUTPUT$FILENAME" -hide_banner -y
            else
                segments_create $PLAYLIST $MOVIENAME
            fi
        fi
        echo "----------------------------------------------"
    done
}
