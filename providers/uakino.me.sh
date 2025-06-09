#!/bin/bash

DIR_TMP="$DIR_TMP/uakino"

uakino_get_single_iframe_video_url() {
    cat "$DIR_TMP-main.html" |
    hxselect -i "div.box.full-text.visible iframe" |
    hxwls
} 

uakino_get_list_id() {
    echo $1 |
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    hxnormalize -x | 
    sed -n 's/.*data-news_id="\([^"]\+\).*/\1/p'
}

uakino_download_movie_page() {
    echo $1 |
    wget -q -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    hxnormalize -x > "$DIR_TMP-main.html"
}

uakino_get_list_id_locally() {
    cat "$DIR_TMP-main.html" |
    sed -n 's/.*data-news_id="\([^"]\+\).*/\1/p'
}

uakino_get_timestamp_locally() {
    cat "$DIR_TMP-main.html" |
    sed -n 's/.*var dle_edittime.*= \([^"]\+\).*\;/\1/p' | 
    tr -d "'"
}

uakino_get_json_list() {
    # TODO Get PHPSESSID and cookies. curl is not working.

    curl "https://uakino.me/engine/ajax/playlists.php?news_id=$1&xfield=playlist&time=$2" \
        -H 'accept: application/json, text/javascript, */*; q=0.01' \
        -H 'accept-language: en-US,en;q=0.9' \
        -H 'cache-control: no-cache' \
        -H 'pragma: no-cache' \
        -H 'priority: u=1, i' \
        -H "referer: $URL" \
        -H 'sec-ch-ua: "Chromium";v="135", "Not-A.Brand";v="8"' \
        -H 'sec-ch-ua-mobile: ?0' \
        -H 'sec-ch-ua-platform: "Linux"' \
        -H 'sec-fetch-dest: empty' \
        -H 'sec-fetch-mode: cors' \
        -H 'sec-fetch-site: same-origin' \
        -H 'user-agent: Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36' \
        -H 'x-requested-with: XMLHttpRequest' > "$DIR_TMP-playlist.php"

    ELEMENTS=$(cat "$DIR_TMP-playlist.php" |
        jq -r .response | # get value by response key
        hxnormalize -x | # normalize html
        hxselect -i "li[data-file]"
    )
    echo $ELEMENTS

    if [ -z $ELEMENTS ]; then
        echo "No voices players found."
        return 1
    fi

    # hxselect -i $SELECTOR | # select videos only from requested playlist
    LISTS=$(
        echo $ELEMENTS | sed 's/data-file/href/g' | sed 's/<li /<a /g' | hxwls
    )

    TITLES=$(
        echo $ELEMENTS | sed 's/data-voice/href/g' | sed 's/<li /<a /g' | hxwls
    )
    TITLES=($TITLES)
    LISTS=($LISTS)

    # Build the pattern (1|2|3|4|5)
    pattern=""
    for i in "${!TITLES[@]}"; do
        if [ -z "$pattern" ]; then
            pattern="$((i+1))"
        else
            pattern="$pattern|$((i+1))"
        fi
    done

    echo "Choose an option (1-${#TITLES[@]}):"
    for i in "${!TITLES[@]}"; do
        echo "$((i+1)). ${TITLES[i]}"
    done

    while true; do
        read -n 1 -s choice
        case $choice in
            [$pattern])
                echo "You chose: ${TITLES[$((choice-1))]}"
                break
                ;;
            *)
                echo "Invalid choice. Please press 1-${#TITLES[@]}."
                ;;
        esac
    done

    IFRAMES_LIST="${LISTS[$((choice-1))]}"
}

uakino_get_json_list2() {
    echo "https://uakino.me/engine/ajax/playlists.php?news_id=$1&xfield=playlist" |
    wget -O- -i- --no-verbose --quiet | 
    jq -r .response | # get value by response key
    hxnormalize -x | # normalize html
    hxselect -i "li[data-id=\"0\"]" | # select videos only from first player
    sed 's/data-file/href/g' | #replacements to make hxwls work
    sed 's/<li /<a /g' |  #replacements to make hxwls work
    hxwls
} 

uakino_get_main_playlist_in_iframe() {
    cat "$DIR_TMP-iframe-video.html" |
    grep -E -o 'file:"(.*)m3u8' | 
    sed -n 's/file:"//p'
}

uakino_get_subtitles_in_iframe() {
    cat "$DIR_TMP-iframe-video.html" |
    grep -E -o 'subtitle:"(.*)"' | 
    sed -n 's/subtitle:"//p' | sed -n 's/"//p'
} 

uakino_get_quality_playlist() {
    echo $1 |
    wget -O- -i- --no-verbose --quiet | 
    grep -E -o "https://(.*)hls\/$QUALITY\/(.*)m3u8"
} 

uakino_get_filename_from_url() {
    if [ "$SOUND" == "0" ]
    then
        echo $1 |
        sed 's/\/hls.*//' |  # remove text before hls
        sed 's#.*/##' # leave only last word
        #https://s2.ashdi.vip/content/stream/serials/stranger_thing_s2/stranger_things__s02e09__chapter_nine._the_gate_65844/hls/
    else
        # get episode num first
        EPISODE=$(echo $1 | sed 's/\/hls.*//' | sed 's#.*/##')
        NAME=$(echo $1 | sed "s/\/$EPISODE.*//" | sed 's#.*/##') 
        echo "$NAME"_"$EPISODE"
        #https://s2.ashdi.vip/video/serials/love_death__robots_s1/1_61061/hls/
    fi
}

init_segments_lists() {
    # Download the page.
    uakino_download_movie_page $URL
    # First approach is to load series by default.
    PLAYLIST_ID=$(uakino_get_list_id_locally)
    TIMESTAMP=$(uakino_get_timestamp_locally)

    debug_log "Playlist ID = $PLAYLIST_ID"
    debug_log "timestamp = $TIMESTAMP"

    IFRAMES_LIST=""
    # Get the lists with propmt.
    uakino_get_json_list $PLAYLIST_ID $TIMESTAMP
    debug_log $IFRAMES_LIST

    if [ -z "$IFRAMES_LIST" ]; then
        echo "No iframes found for series download. Trying another approach."
        IFRAMES_LIST=($(uakino_get_json_list2 $PLAYLIST_ID))
        debug_log $IFRAMES_LIST
    fi

    # Try to load single video approach.
    if [ -z "$IFRAMES_LIST" ]; then
        echo "Trying approach with single movie download."
        VIDEO_URL=$(uakino_get_single_iframe_video_url)
        IFRAMES_LIST=($VIDEO_URL)
        debug_log $IFRAMES_LIST
    fi

    if [ -z "$IFRAMES_LIST" ]; then
        echo "No iframes found. exit"
        exit
    fi
    
    TOTAL_ITEMS=(${#IFRAMES_LIST[@]})
    debug_log "Total episodes before skip: $TOTAL_ITEMS"

    # Removing first skipped videos.
    if [ $SKIP -gt 0 ]; then
        for (( i=0;i<$(($SKIP));i++)); do
            #echo "skip ${IFRAMES_LIST[${i}]}"
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
        # Save video iframe.
        echo $iframe |
        sed  's/https://' | sed 's/\/\//https:\/\//' | 
        wget -q -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
        hxnormalize -x > "$DIR_TMP-iframe-video.html"

        VIDEO_URI=$(uakino_get_main_playlist_in_iframe $iframe)
        echo "playlist main = $VIDEO_URI"
        PLAYLIST=$(uakino_get_quality_playlist $VIDEO_URI)
        echo "playlist quality = $PLAYLIST"
        if [ -z "$PLAYLIST" ]; then
            echo "Playlist for selected quality not found. Try another."
            exit
        fi
        MOVIENAME=$(uakino_get_filename_from_url $VIDEO_URI)
        FILENAME="$MOVIENAME.mp4"
        #echo "filname = $FILENAME"
        
        if [ "$DRY_RUN" == "0" ] 
        then
            if [ "$USE_FFMPEG_DOWNLOADER" == "1" ]; then
                ffmpeg -i $PLAYLIST -c copy -bsf:a aac_adtstoasc "$OUTPUT$FILENAME" -hide_banner -y
            else
                segments_create $PLAYLIST $MOVIENAME 1
            fi
        fi
        echo "----------------------------------------------"
    done
}
