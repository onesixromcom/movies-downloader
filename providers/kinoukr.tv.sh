#!/usr/local/env bash

kinoukr_get_single_iframe() {
    cat "$DIR_TMP-main.html" |
    hxselect -i "iframe" |
    sed 's/src/href/g' |
    sed 's/iframe/a/g' |
    hxwls |
    head -n 1 # Only first player selection
}

kinoukr_tv_get_iframe_list() {
    IFRAMES_LIST=($(kinoukr_get_single_iframe))
}
