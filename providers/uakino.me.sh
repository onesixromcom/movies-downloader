#!/bin/bash

uakino_get_single_iframe_video_url() {
    echo $1 |
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    hxnormalize -x | 
    hxselect -i "div.box.full-text.visible iframe" |
    hxwls
} 

uakino_get_list_id() {
    echo $1 |
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    hxnormalize -x | 
    sed -n 's/.*data-news_id="\([^"]\+\).*/\1/p'
}   

uakino_get_json_list() {
    declare SELECTOR=$(echo "li[data-id=\"${PLAYLIST_NUM}\"]")
    echo "https://uakino.me/engine/ajax/playlists.php?news_id=$1&xfield=playlist" |
    wget -O- -i- --continue --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 | 
    jq -r .response | # get value by response key
    hxnormalize -x | # normalize html
    # TODO: check the bug with $PLAYLIST_NUM
    # "li[data-id=\"$PLAYLIST_NUM\"]"
    hxselect -i $SELECTOR | # select videos only from requested playlist
    sed 's/data-file/href/g' | #replacements to make hxwls work
    sed 's/<li /<a /g' |  #replacements to make hxwls work
    hxwls
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
    echo $1 |
    sed  's/https://' | sed 's/\/\//https:\/\//' | 
    wget -O- -i- --no-verbose --quiet | 
    grep -E -o 'file:"(.*)m3u8' | 
    sed -n 's/file:"//p'
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
    
    # First approach is to load series by default.
    PLAYLIST_ID=$(uakino_get_list_id $URL)
    debug_log "Playlist ID = $PLAYLIST_ID"

    IFRAMES_LIST=($(uakino_get_json_list $PLAYLIST_ID))
    debug_log $IFRAMES_LIST
    if [ -z "$IFRAMES_LIST" ]; then
        echo "No iframes found for series download. Trying another approach."
        IFRAMES_LIST=($(uakino_get_json_list2 $PLAYLIST_ID))
        debug_log $IFRAMES_LIST
    fi

    # Try to load single video approach.
    if [ -z "$IFRAMES_LIST" ]; then
        echo "Trying approach with single movie download."
        VIDEO_URL=$(uakino_get_single_iframe_video_url $URL)
        IFRAMES_LIST=($VIDEO_URL)
        debug_log $IFRAMES_LIST
    fi

    if [ -z "$IFRAMES_LIST" ]; then
        echo "No iframes found. exit"
        exit
    fi
    
    TOTAL_ITEMS=(${#IFRAMES_LIST[@]})
    debug_log "total before skip: $TOTAL_ITEMS"

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
