#!/usr/local/env bash

uakino_get_single_iframe_video_url() {
    cat "$DIR_TMP-main.html" |
    hxselect -i "div.box.full-text.visible iframe" |
    hxwls |
    sed 's/geoblock=ua//p'
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
    echo "Getting the playlist for news $1 with timestamp $2"
    $CURL_ORIG \
    --ciphers TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384,TLS_CHACHA20_POLY1305_SHA256,ECDHE-ECDSA-AES128-GCM-SHA256,ECDHE-RSA-AES128-GCM-SHA256,ECDHE-ECDSA-AES256-GCM-SHA384,ECDHE-RSA-AES256-GCM-SHA384,ECDHE-ECDSA-CHACHA20-POLY1305,ECDHE-RSA-CHACHA20-POLY1305,ECDHE-RSA-AES128-SHA,ECDHE-RSA-AES256-SHA,AES128-GCM-SHA256,AES256-GCM-SHA384,AES128-SHA,AES256-SHA \
    -H 'sec-ch-ua: "Chromium";v="104", " Not A;Brand";v="99", "Google Chrome";v="104"' \
    -H 'sec-ch-ua-mobile: ?0' \
    -H 'sec-ch-ua-platform: "Windows"' \
    -H 'Upgrade-Insecure-Requests: 1' \
    -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/104.0.0.0 Safari/537.36' \
    -H 'Accept: application/json, text/javascript, */*; q=0.01' \
    -H 'Sec-Fetch-Site: none' \
    -H 'Sec-Fetch-Mode: cors' \
    -H 'Sec-Fetch-User: ?1' \
    -H "referer: $URL" \
    -H 'Sec-Fetch-Dest: empty' \
    -H 'Accept-Encoding: gzip, deflate, br' \
    -H 'Accept-Language: en-US,en;q=0.9' \
    -H 'x-requested-with: XMLHttpRequest' \
    --http2 --compressed \
    --tlsv1.2 --no-npn --alps \
    --cert-compression brotli \
    "https://uakino.best/engine/ajax/playlists.php?news_id=$1&xfield=playlist&time=$2" \
    > "$DIR_TMP-playlist.php" 

    # Flag if we use series flow.
    SERIES_FLOW="1"

    # Get players voices for series.
    PLAYLISTS=$(cat "$DIR_TMP-playlist.php" |
        jq -r .response |
        hxnormalize -x |
        tr -d '\n' | tr -d '\r' |
        sed -e $'s/   / /g' |
        hxselect -i "div.playlists-lists div.playlists-items ul")
    
    # There are no series if it's empty. Try voices flow.
    if [ -z "$PLAYLISTS" ]; then
        PLAYLISTS=$(cat "$DIR_TMP-playlist.php" |
            jq -r .response | # get value by response key
            hxnormalize -x | # normalize html
            tr -d '\n' | tr -d '\r' |
            sed -e $'s/   / /g' |
            hxselect -i "li[data-file]"
        )
        SERIES_FLOW=""
    fi

    readarray -t TITLES < <(echo "$PLAYLISTS" | hxselect -i "li[data-id]" | sed 's/<li[^>]*>\([^<]*\)<\/li>/\1\n/g' | grep -v '^$')
    
    if [ -z "$TITLES" ]; then
        echo "No voices players found."
        return 1
    fi
    choice=1;

    # Select the only voice available automatically.
    if [ "1" -ne "${#TITLES[@]}" ] && [ "$AUTOVOICE" -eq 0 ]; then
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
    else
        echo "Automatic choice: ${TITLES[$((choice-1))]}"
    fi

    if [ -z $SERIES_FLOW ]; then
        readarray -t LISTS < <(echo "$PLAYLISTS" | grep -o 'data-file="[^"]*"' | sed 's/data-file="//g' | sed 's/"//g')
        IFRAMES_LIST="${LISTS[$((choice-1))]}"
    else
        ELEMENTS=$(cat "$DIR_TMP-playlist.php" |
            jq -r .response | # get value by response key
            hxnormalize -x |
            hxselect -i "div.playlists-videos li[data-id='0_$((choice-1))']"
        )

        if [ -z "$ELEMENTS" ]; then
            echo "Incorrect choice."
            return 1
        fi

        readarray -t IFRAMES_LIST < <(echo "$ELEMENTS" | grep -o 'data-file="[^"]*"' | sed 's/data-file="//g' | sed 's/"//g')
    fi
}

uakino_get_json_list2() {
    echo "https://uakino.best/engine/ajax/playlists.php?news_id=$1&xfield=playlist" |
    wget -O- -i- --no-verbose --quiet | 
    jq -r .response | # get value by response key
    hxnormalize -x | # normalize html
    hxselect -i "li[data-id=\"0\"]" | # select videos only from first player
    sed 's/data-file/href/g' | #replacements to make hxwls work
    sed 's/<li /<a /g' |  #replacements to make hxwls work
    hxwls
} 

uakino_best_get_filename_from_url() {
    if [ "$SOUND" == "0" ]
    then
        echo $1 |
        sed 's/\/hls.*//' |  # remove text before hls
        sed 's#.*/##' # leave only last word
    else
        # Get the episode num first.
        EPISODE=$(echo $1 | sed 's/\/hls.*//' | sed 's#.*/##')
        NAME=$(echo $1 | sed "s/\/$EPISODE.*//" | sed 's#.*/##') 
        echo "$NAME"_"$EPISODE"
    fi
}

uakino_best_get_iframe_list() {
    # First approach is to load series by default.
    PLAYLIST_ID=$(uakino_get_list_id_locally)
    TIMESTAMP=$(uakino_get_timestamp_locally)

    # Get the lists with propmt.
    if [ ! -z "$PLAYLIST_ID" ]; then
        uakino_get_json_list $PLAYLIST_ID $TIMESTAMP
        debug_log $IFRAMES_LIST

        if [ -z "$IFRAMES_LIST" ]; then
            echo "No iframes found for series download. Trying another approach."
            IFRAMES_LIST=($(uakino_get_json_list2 $PLAYLIST_ID))
            debug_log $IFRAMES_LIST
        fi
    fi

    # Try to load single video approach.
    if [ -z "$IFRAMES_LIST" ]; then
        echo "Trying approach with single movie download."
        IFRAMES_LIST=($(uakino_get_single_iframe_video_url))
        debug_log $IFRAMES_LIST
    fi
}
