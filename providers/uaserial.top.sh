#!/bin/bash

# Script is working only with --use-ffmpeg flag since playlist has no absolute urls to the videos.

SUPPORTED_SERVICES=("ashdi.vip","boogiemovie.online")

DOMAIN=""
# Get the list of embed urls.
uaserial_get_embed_list() {
    echo $1 |
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    hxnormalize -x | # normalize html
    hxselect -i "select[id=\"select-series\"]" | # select videos only from first player
    sed 's/value/href/g' | #replacements to make hxwls work
    sed 's/<option /<a /g' |  #replacements to make hxwls work
    hxwls
}

uaserial_get_single_src() {
    echo $1 |
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    # cat ./tmp/movie-blackberry.html |
    hxnormalize -x | # normalize html
    hxselect -i "iframe[id=\"embed\"]" | # select videos only from first player
    sed 's/src/href/g' | #replacements to make hxwls work
    sed 's/iframe/a/g' | #replacements to make hxwls work
    hxwls
}

# Get the player iframes url from the service url.
# Each link is the different player/voice
uaserial_get_service_iframe_url() {
    echo $1 |
    # cat "./tmp/episode-1.html" | 
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    hxnormalize -x | 
    hxselect -i "select[class=\"voices__select\"]" |
    sed 's/value/href/g' |
    sed 's/<option /<a /g' |
    hxwls
}

uaserial_get_main_playlist_in_iframe() {
    if [ "$DOMAIN" == "ashdi.vip" ] 
    then
        echo $1 |
        wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
        grep -E -o 'file:"(.*)m3u8' | 
        sed -n 's/file:"//p'
    fi

    if [ "$DOMAIN" == "boogiemovie.online" ] 
    then
        echo $1 |
        wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
        # cat ./tmp/9829.html |
        hxnormalize -x |
        grep -E -o "manifest: '(.*)m3u8'," | 
        sed -n "s/manifest: '\(.*\)',/\1/p"
    fi

    echo ""
}


# Get quality playlist from main playlist.
uaserial_com_get_quality_playlist() {
    if [ "$DOMAIN" == "ashdi.vip" ] 
    then
        echo $1 |
        wget -O- -i- --no-verbose --quiet | 
        grep -E -o "https://(.*)hls\/$QUALITY\/(.*)m3u8"
    fi

    if [ "$DOMAIN" == "boogiemovie.online" ] 
    then
        echo $1 |
        wget -O- -i- --no-verbose --quiet | 
        # cat "./tmp/master.m3u8" |
        grep -E -o "^https://(.*)\/$QUALITY.mp4\/(.*)m3u8"
    fi

    echo ""
} 


uaserial_get_filename_from_url() {
    local playlist_url=$1
    if [ "$DOMAIN" == "ashdi.vip" ] 
    then
        # https://ashdi.vip/video26/1/new/somename_s01ep01_163460/hls/480/BKeMlHWLlPtdnwbhDos=/index.m3u8
        if [ `expr "$playlist_url" : ".*==.*"` -gt 0 ];
        then
            echo $playlist_url | sed 's/\/hls\/.*//'  | sed 's#.*/##'
        else
            echo $playlist_url | sed 's/\/hls.*//' | sed 's#.*/##'
        fi
    fi

    # Get the movie name from iframe url
    if [ "$DOMAIN" == "boogiemovie.online" ] 
    then
        echo $2 |
        sed -E 's|^.*/embed/||' |
        sed 's|/|_|g'
    fi

    echo ""
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

    # If there are no series selector try to get iframe src
    if [ $TOTAL_ITEMS -eq 0 ]; then
        IFRAMES_LIST=($(uaserial_get_single_src $URL))
    fi

    TOTAL_ITEMS=(${#IFRAMES_LIST[@]})

    if [ $TOTAL_ITEMS -eq 0 ]; then
        echo "No embed links were found."
        exit
    fi

    for iframe in "${IFRAMES_LIST[@]}";
    do
        echo $iframe
    done

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
        echo "IFRAME = $iframe"
        local IFRAME_URL="https://uaserial.top$iframe"
        SERVICE_IFRAMES=($(uaserial_get_service_iframe_url $IFRAME_URL))
        local SERVICE_IFRAME=${SERVICE_IFRAMES[$PLAYLIST_NUM]}
        echo "SERVICE IFRAME = ${SERVICE_IFRAMES[$PLAYLIST_NUM]}"
        DOMAIN=$(extract_domain $SERVICE_IFRAME)
        
        PLAYLIST_MAIN=$(uaserial_get_main_playlist_in_iframe $SERVICE_IFRAME)
        echo "playlist main = $PLAYLIST_MAIN"

        PLAYLIST_QUALITY=$(uaserial_com_get_quality_playlist $PLAYLIST_MAIN)
        debug_log "Playlist quality = $PLAYLIST_QUALITY"
        if [ -z "$PLAYLIST_QUALITY" ]; then
            echo "Playlist for selected quality not found. Try another."
            exit
        fi
        
        MOVIENAME=$(uaserial_get_filename_from_url $PLAYLIST_MAIN $IFRAME_URL)
        FILENAME="$MOVIENAME.mp4"
        debug_log "Filename to save $FILENAME";

        # segments not working for boogiemovie.
        if [ "$DRY_RUN" == "0" ] 
        then
            if [ "$USE_FFMPEG_DOWNLOADER" == "1" ]; then
                ffmpeg -i $PLAYLIST_QUALITY -c copy -bsf:a aac_adtstoasc "$OUTPUT$FILENAME" -hide_banner -y
            else
                segments_create $PLAYLIST_QUALITY $MOVIENAME 1
            fi
        fi
        echo "----------------------------------------------"
    done
}
