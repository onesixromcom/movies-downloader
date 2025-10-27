#!/usr/local/env bash

DIR_TMP="$DIR_TMP/kinoukr"

kinoukr_get_single_iframe() {
    cat "$DIR_TMP-main.html" |
    hxselect -i "iframe" |
    sed 's/src/href/g' |
    sed 's/iframe/a/g' |
    hxwls |
    head -n 1 # Only first player selection
}

init_segments_lists() {
    # Download the page.
    movie_download_main_page
    
    echo "Getting iframe urls from the page."
    IFRAME_URL=$(kinoukr_get_single_iframe)

    if [ -z "$IFRAME_URL" ]; then
        echo "Iframe url was not found."
        exit
    fi

    echo "Iframe URL: $IFRAME_URL"

    # Get video uri.
    VIDEO_URI=$(movie_get_main_playlist $IFRAME_URL)
    
    if [ -z "$VIDEO_URI" ]; then
        echo "Playlist for selected quality not found. Try another."
        exit
    fi

    # Split strings to an array.
    PLAYLISTS_URLS=($(echo "$VIDEO_URI" | tr ',' '\n'))

    for PLAYLISTS_URL in "${PLAYLISTS_URLS[@]}";
    do
        PLAYLIST=$(movie_get_quality_playlist $PLAYLISTS_URL)

        echo "Process $PLAYLISTS_URL"
            
        if [ -z "$PLAYLIST" ]; then
            echo "Playlist for selected quality not found. Try another."
            exit
        fi

        echo "Playlist quality = $PLAYLIST"

        # Get subtitles.
        SUBTITLES=""
        MOVIENAME=$(movie_get_filename_from_url $PLAYLISTS_URL)
        FILENAME="$MOVIENAME.mp4"
        echo "Movie filename = $FILENAME"
        
        if [ "$DRY_RUN" == "0" ];
        then
            if [ "$USE_FFMPEG_DOWNLOADER" == "1" ]; then
                ffmpeg -i $PLAYLIST -c copy -bsf:a aac_adtstoasc "$OUTPUT$FILENAME" -hide_banner -y
            else
                segments_create $PLAYLIST $MOVIENAME 1 $SUBTITLES
            fi
        fi
        echo "----------------------------------------------"
    done
}
