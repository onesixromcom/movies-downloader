#!/bin/bash

# Tests for Universal Movie downloader to check
# if everything is working after after some time.

# Colors 
CRed='\033[0;31m'
CGreen='\033[0;32m'
CBlue='\033[0;34m'
CPurple='\033[0;35m'
CN='\033[0m' # No Color

test_file_is_present() {
    local filepath=$1
    if test -f "$filepath"; then
        echo "File is present: $filepath"
    else
        echo "Error! File is not present: $filepath"
        SUCCESS_FLAG=0
    fi
}

test_kinosite() {
    local url=$1
    local domain=$(extract_domain $url)

    echo -e "$CBlue Testing $domain $CN"
    source movie.sh --clear --skip-download $url

    SUCCESS_FLAG=1
    movie_process_urls

    test_file_is_present "$DIR_TMP-main.html"
    test_file_is_present "$DIR_TMP-playlist.m3u8"
    test_file_is_present "$DIR_TMP-playlists.m3u8"

    if [ "$SUCCESS_FLAG" -eq 1 ]; then
        echo -e "$CGreen ++++ Tests were success! ++++ $CN"
    else
        echo -e "$CRed ---- There were errors in tests! ---- $CN"
    fi
}

# Testing uafix.net
test_kinosite https://uafix.net/cartoons/hoppersv2/

# Testing uaserials.com
test_kinosite https://uaserials.com/12349-voll-i.html

# Tests kinoukr.tv
test_kinosite https://kinoukr.tv/8933-super-mario-galaktyka-v-kino.html

# Tests uakino.best
test_kinosite https://uakino.best/cartoon/short_cartoons/33641-skubi-du-rizdvo.html
