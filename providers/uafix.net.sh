#!/usr/local/env bash

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

uafix_net_get_iframe_list() {
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
}
