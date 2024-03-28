#!/bin/bash


# Updated versions after the bracets where changes on website.
uaserials_get_player_encoded() {
	echo $1 |
	wget -O- -i- --no-verbose --quiet | 
	hxnormalize -x |
	sed -n "s/.*data-tag1='\([^']\+\).*/\1/p"
}

# Get playlist link from iframe.
uaserials_get_main_playlist_in_iframe() {
	echo $1 |
	sed  's/https://' | sed 's/\/\//https:\/\//' | 
	wget -O- -i- --no-verbose --quiet | 
	grep -E -o 'file:"(.*)m3u8' | 
	sed -n 's/file:"//p'
} 

# Get quality playlist from main playlist.
uaserials_get_quality_playlist() {
	echo $1 |
	wget -O- -i- --no-verbose --quiet | 
	grep -E -o "https://(.*)hls\/$QUALITY\/(.*)m3u8"
} 

# Create filename from playlist url.
# exmaple url https://sparrow.tortuga.wtf/hls/serials/solar.opposites.s01e08.adrianzp.mvo_45026/hls/index.m3u8
uaserials_get_filename_from_url() {
	echo $1 |
	sed 's/\/hls\/index.*//' |  # remove text after hls/index
	sed 's#.*/##' # leave only last word
}

init_segments_lists() {
	# Get iframes for players.
	PLAYER_ENCODED=$(uaserials_get_player_encoded $URL)
	#PLAYER_ENCODED=$(get_player_encoded ./page.html)

	if [ -z "$PLAYER_ENCODED" ]; then
		echo "Decoded player url was not found."
		exit
	fi

	#echo "PLAYER JSON: $PLAYER_ENCODED"
	#echo "$PLAYER_ENCODED" > ./player.json

	PLAYER_IFRAMES=$(node ./scripts/crypto.js $PLAYER_ENCODED $SEASON $SOUND)

	if [ -z "$PLAYER_IFRAMES" ]; then
		echo "No iframes for player found. exit"
		exit
	fi
	echo "player iframes: $PLAYER_IFRAMES"


	# Split strings to an array.
	PLAYER_IFRAMES=($(echo "$PLAYER_IFRAMES" | tr ',' '\n'))

	TOTAL_ITEMS=(${#PLAYER_IFRAMES[@]})
	echo "total before skip: $TOTAL_ITEMS"

	# Skip videos from beginning.
	if [ $SKIP -gt 0 ]; then
		for (( i=0;i<$(($SKIP));i++)); do
			echo "skip ${PLAYER_IFRAMES[${i}]}"
			unset PLAYER_IFRAMES[$i]
		done
	fi
	
	
	# Unset video we dont want to download.
	if [ $TOTAL -gt 0 ]; then
		echo "Set total frames to: $TOTAL"
		for (( i = $(($SKIP)) + $(($TOTAL)); i < (($TOTAL_ITEMS)); i++ )); do
			#echo "unset ${IFRAMES_LIST[${i}]}"
			unset PLAYER_IFRAMES[$i]
		done
	fi

	[ -d $OUTPUT ] || mkdir -p $OUTPUT

	for iframe in "${PLAYER_IFRAMES[@]}";
	do
		VIDEO_URI=$(uaserials_get_main_playlist_in_iframe $iframe)
		echo "playlist main = $VIDEO_URI"
		PLAYLIST=$(uaserials_get_quality_playlist $VIDEO_URI)
		echo "playlist quality = $PLAYLIST"
		if [ -z "$PLAYLIST" ]; then
			echo "Playlist for selected quality not found. Try another."
			exit
		fi
		
		MOVIENAME=$(uaserials_get_filename_from_url $VIDEO_URI)
		FILENAME="$MOVIENAME.mp4"
		#echo "filname = $FILENAME"
		#echo $FILENAME > $FILE_VIDEO_NAME

		if [ "$DRY_RUN" == "0" ] 
		then
			if [ "$USE_FFMPEG_DOWNLOADER" == "1" ]; then
				ffmpeg -i $PLAYLIST -c copy -bsf:a aac_adtstoasc "$OUTPUT$FILENAME" -hide_banner -y
			else
				segments_create $PLAYLIST $MOVIENAME
			fi
		fi
		echo "----------------------------------------------"
	done
	
}
