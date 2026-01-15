
DIR_TMP="$DIR_TMP/uafix"

uafix_get_iframes() {
    cat "$1" |
    hxselect -i "iframe" |
    sed 's/src/href/g' |
    sed 's/iframe/a/g' |
    hxwls |
    head -n 1 # Only first player selection
}

uafix_get_iframe_lists() {
    local urls=($(cat "$DIR_TMP-main.html" |
        hxnormalize -x | hxselect -i "a.vi-img.img-resp-h" | sed 's/src=/ddd=/g' | hxwls))
    local iframe_list=()
    for url in "${urls[@]}";
    do
        movie_download_main_page "$url"
        local iframe_url=$(uafix_get_iframes "$DIR_TMP-main.html")
        iframe_list+=("$iframe_url")
    done

    echo "${iframe_list[@]}"
}

init_segments_lists() {
    # Download the page.
    movie_download_main_page

    # First approach is to get iframe from the page.
    IFRAME_URL=$(uafix_get_iframes "$DIR_TMP-main.html")
    IFRAMES_LIST=()
    # Try to load iframes from the series pages
    if [ -z "$IFRAME_URL" ]; then
        echo "Iframe absent, try load series"
        IFRAMES_LIST=($(uafix_get_iframe_lists))
    else
        echo "Iframe was found on the page: $IFRAME_URL"
        IFRAMES_LIST+=($IFRAME_URL)
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

    for iframe_url in "${IFRAMES_LIST[@]}";
    do
        echo "IFRAME = $iframe_url"

        local iframe_file=$(get_temp_iframe_filename "$iframe_url")

        curl "$iframe_url" \
        -H 'accept: text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7' \
        -H 'accept-language: en-US,en;q=0.9' \
        -H 'referer: https://uafix.net/' > "$iframe_file"

        # Get video uri.
        VIDEO_URI=$(movie_get_main_playlist "$iframe_url" "$iframe_file")

        if [ -z "$VIDEO_URI" ]; then
            echo "Playlist for selected quality not found. Try another."
            exit
        fi

        echo "Playlist main = $VIDEO_URI"

        PLAYLIST=$(movie_get_quality_playlist $VIDEO_URI)
        
        if [ -z "$PLAYLIST" ]; then
            echo "Playlist for selected $QUALITY quality not found. Try another."
            exit
        fi

        echo "Playlist quality = $PLAYLIST"

        # Get subtitles.
        SUBTITLES=$(movie_get_subtitles $iframe_url)
        MOVIENAME=$(movie_get_filename_from_url $VIDEO_URI)
        FILENAME="$MOVIENAME.mp4"
        echo "Movie filename = $FILENAME"
        
        if [ "$DRY_RUN" == "0" ];
        then
            if [ "$USE_FFMPEG_DOWNLOADER" == "1" ]; then
                ffmpeg -i $PLAYLIST -c copy -bsf:a aac_adtstoasc "$OUTPUT$FILENAME" -hide_banner -y
            else
                segments_create $PLAYLIST $MOVIENAME 0 $SUBTITLES
            fi
        fi
        echo "----------------------------------------------"
    done
}
