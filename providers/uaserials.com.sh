#!/usr/local/env bash

# New version of the websites uses now js object format and PlayerJS to handle all data.
# 1. First we need to get iframe url.
# 2. extract json data from the page
# 3. get all playlist files with required quality 

# Specify Season and Voice via cli param.
# ./movie.sh https://uaserials.com/some-cartoon --season=1 --voice=1

# Get encoded tag1 from iframe
uaserials_com_get_player_tag1() {
    cat "$DIR_TMP-main.html" |
    hxselect -i "player-control" |
    grep -o 'data-tag1="[^"]*"' | 
    sed 's/data-tag1="\(.*\)"/\1/' |
    sed 's/&#34;/"/g' # replace double quotes symbol after normalization
}

# Get iframe url.
uaserials_get_iframe_url() {
    cat "$DIR_TMP-main.html" |
    sed -n "s/.*data-src=\"\([^']\+\)embed\(.*\)\"/\1embed\2/p"
}

# Return the list of playlists. Playlist should have links to qualities playlist
# Input params is the url to iframe.
uaserials_get_main_playlists() {
    curl -s -L --retry 5 --retry-connrefused --connect-timeout 15 --max-time 20 "$1" \
    -H 'referer: https://uaserials.com/' |
    # hxnormalize -x > "$file_iframe"
    hxnormalize -x |
    sed -n '/new Playerjs({/,/});/p' | 
    sed -e 's/.*new Playerjs(\({.*}\)).*/\1/' > $DIR_TMP/player.js
    # Create concatenated js and process it.
    cat $DIR_SCRIPTS/uaserials_pro_begin.js $DIR_TMP/player.js $DIR_SCRIPTS/uaserials_pro_end.js > $DIR_TMP/uaserials_pro.js
    rm $DIR_TMP/player.js
    echo $(node $DIR_TMP/uaserials_pro.js "$SEASON")
}

uaserials_com_get_iframe_list() {
    # Try the flow with encoded player first.
    local TAG1_ENCODED=$(uaserials_com_get_player_tag1)

    if [ ! -z $TAG1_ENCODED ]; then
        echo "+ Decrypting tag1"
        IFRAMES_LIST=$(node ./scripts/uaserials_com_crypto.js $TAG1_ENCODED $SEASON $VOICE)
    fi

    if [ -z $IFRAMES_LIST ]; then
        # Get iframes url.
        echo "Getting iframe urls from the page."
        IFRAME_URL=$(uaserials_get_iframe_url)

        if [ -z "$IFRAME_URL" ]; then
            echo "Iframe url was not found."
            exit
        fi

        echo "Iframe URL: $IFRAME_URL"

        IFRAMES_LIST=$(uaserials_get_main_playlists $IFRAME_URL)
    fi
}
