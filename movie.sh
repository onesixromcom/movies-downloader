#!/bin/bash

# Universal movies dowloader.

# Install before use:
# sudo apt install html-xml-utils wget ffmpeg jq curl openssl python3

DIR=$(dirname $(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null||echo $0))

VERSION="0.7"
PROGRAM_NAME="Universal Movies Downloader"
SUPPORTER_PROVIDERS=("uaserials.com" "uakino.best" "uaserial.top" "prmovies.beer")
PROVIDER_NAME=""
# Quality: 480, 720, 1080 if available
QUALITY="480"
# Specific season for show.
SEASON=0
# Set Audio track.
SOUND=0
VOICE=0
# Playlist number (for uaserials.top).
PLAYLIST_NUM=""
# Will create all files needed for queue download or check if movie is available for download in case of using ffmpeg downloader.
DRY_RUN="0"
# Set folder to download movie.
OUTPUT="/home/$USER/Videos/movies/"
OUTPUT_SEGMENTS=$OUTPUT
OUTPUT_SEGMENTS+="segments"
PARSE_SEGMENTS="segment"
# Skip first N videos from season.
SKIP=0
# Set how many videos to download
TOTAL="0"
# Additional flag to enable FFmepeg downloader.
USE_FFMPEG_DOWNLOADER=0
# Debug flag to save all downloaded files
DEBUG="0"

DIR_SCRIPTS="./scripts"
# Temp files to store info.
DIR_TMP="./tmp"
# Variables files to store movies info.
VARS_DIR="./vars"
FILE_QUEUE="$VARS_DIR/queue.list"
FILE_FFMPEG_LIST="$VARS_DIR/list-ffmpeg.txt"
# List of links to download with wget.
FILE_WGET_LIST="$VARS_DIR/wget-src.list"
# Destination location for each link
FILE_WGET_DEST="$VARS_DIR/wget-dest.list"
# Store line counter
FILE_COUNTER="$VARS_DIR/counter"
FILE_VIDEO_NAME="$VARS_DIR/video-name"

# Get url from first argument.
args=("$@") 
URL=${args[0]}
unset args[0]

# Colors 
CGreen='\033[0;32m'        # Green
CN='\033[0m' # No Color

# Check if link to page is present.
if [ -z "$URL" ]; then
    echo "No url supplied. Please set collection name. (ex: https://uaserials.pro/filmy/genre-action/some-movie.html)"
    echo -e "Downloader works with websites: $CGreen ${SUPPORTER_PROVIDERS[*]} $CN"
    printf 'You can use additional parameters: 
\t--sound=N\tSet Audio track.
\t--quality=N\tQuality: 480, 720, 1080 if available
\t--dry-run\tWill create all files needed for queue download or check if movie is available for download in case of using ffmepg downloader.
\t--output=PATH\tSet folder to download movie. Default is /home/$USER/Videos/movies
\t--use-ffmpeg\tSwitch to ffmpeg downloader. Could be the issue when one of the segment goes timeout. Download will stuck and will be started from the start next run.
\t--skip=N\tSkip first N videos from season.
\t--total=N\tTotal videos to be downloaded if episodes are available.
\t--playlist=1\tUseful for uakino.club when there are more than 1 season playlists.
\t--clear\tClear previous downloads.
'
    echo -e "Params for $CGreen uaserials.pro: $CN";
    printf '\t--season="1 сезон"\tSpecific season for show in text.'
    exit
fi

for i in "${args[@]}"; do
  case "$i" in
    --season=*)
      SEASON="${i#*=}"
      ;;
    --sound=*)
      SOUND="${i#*=}"
      ;;
    --voice=*)
      VOICE="${i#*=}"
      ;;
    --quality=*)
      QUALITY="${i#*=}"
      ;;
    --dry-run)
      DRY_RUN="1"
      ;;  
    --output=*)
      OUTPUT="${i#*=}"
      ;;
    --use-ffmpeg)
      USE_FFMPEG_DOWNLOADER="1"
      ;;  
    --skip=*)
      SKIP="${i#*=}"
      SKIP=$(($SKIP + 0)) # convert to int
      ;;  
    --total=*)
      TOTAL="${i#*=}"
      TOTAL=$(($TOTAL + 0)) # convert to int
      ;;
    --playlist=*)
      # PLAYLIST_NUM="0_${i#*=}"
      PLAYLIST_NUM=${i#*=}
      ;;
    --debug)
      DEBUG="1"
      ;;
    --help)
      exit
      ;;
    --clear)
      echo "Clear all variables and tmp segments."
      rm -rf $VARS_DIR/*
    #   rm -rf $DIR_TMP/*
      rm -rf $OUTPUT_SEGMENTS/*
      ;;  
    *)
      printf "***************************\n"
      printf "* Error: Invalid argument.*\n"
      printf "***************************\n"
      exit 1
  esac
  shift
done

# =================================================
# ============== Helpers ==========================
# =================================================

# Check if provider from url is supported.
check_supported_provider() {
    if [[ ! " ${SUPPORTER_PROVIDERS[@]} " =~ " $PROVIDER_NAME " ]]; then
        echo "Wrong website name ($PROVIDER_NAME) was used in input.";
        echo "Please use one of:";
        for p in ${SUPPORTER_PROVIDERS[@]}; do echo $p; done;
        exit 1;
    fi
}

# Get host from URL.
get_host() {
    echo $1 |
    awk -F[/:] '{print $4}'
}

# Extract domain from URL
extract_domain() {
    local url="$1"
    # Remove protocol (http://, https://, etc.)
    domain=$(echo "$url" | sed -E 's#^(https?://)?([^/]+).*#\2#')
    # Remove port number if present
    domain=$(echo "$domain" | sed -E 's#(.+):[0-9]+$#\1#')
    echo "$domain"
}

# Show debug info.
debug_log() {
    if [ -z "$DEBUG" ]; then
        return
    fi
    TMP_VAR=$1
    
    if [[ "$(declare -p TMP_VAR)" =~ "declare -a" ]]; then
        for tmp_v in $TMP_VAR
        do
            echo $tmp_v
        done
    else
        echo $TMP_VAR
    fi
}

# Used to create segment url
get_remote_video_folder() {
    echo $1 |
    sed -n 's/index.m3u8//p'
}

# Create files with segments list for wget and ffmpeg.
# param 1 - m3u8 playlist url
# param 2 - movie name
# param 3 - 0/1 to use full video path from playlist
# param 4 - subtitles. [NAME]LINK,[NAME]LINK
segments_create() {
    local playlist_url=$1
    [ -d $OUTPUT_SEGMENTS ] || mkdir -p $OUTPUT_SEGMENTS
    MOVIE_NAME="$2"
    USE_FULL_PATH=0
    MOVIE_SUBTITLES="$4"

    # todo: error here if param is string
    if [ -n "$3" ]; then
      if [ $3 -eq 1 ]; then
          USE_FULL_PATH=1
      fi
    fi

    echo "Creating segments for $playlist_url"
    
    FILE_MOVIE_VARS="$VARS_DIR/$MOVIE_NAME.vars"
    FILE_FFMPEG_LIST="$VARS_DIR/$MOVIE_NAME.ffmpeg"
    
    # Remove previously created files.
    if test -f "$FILE_FFMPEG_LIST"; then
       rm $FILE_FFMPEG_LIST
    fi
    if test -f "$FILE_MOVIE_VARS"; then
       rm $FILE_MOVIE_VARS
    fi
    
    # This solution is working when only segments filenames are present in playlist.
    # Since new updates from uakino.best it's not working.
    VIDEO_FOLDER=$(get_remote_video_folder $playlist_url)

    OUTPUT_MOVIE_SEGMENTS=$OUTPUT_SEGMENTS
    OUTPUT_MOVIE_SEGMENTS+="/$MOVIE_NAME"
    [ -d $OUTPUT_MOVIE_SEGMENTS ] || mkdir -p $OUTPUT_MOVIE_SEGMENTS

    # Save variables per movie.
    echo "MOVIE_FOLDER_SEGMENTS=$OUTPUT_MOVIE_SEGMENTS" >> $FILE_MOVIE_VARS
    echo "MOVIE_NAME=$MOVIE_NAME" >> $FILE_MOVIE_VARS
    echo "MOVIE_FINAL_FILE=$MOVIE_NAME.mp4" >> $FILE_MOVIE_VARS
    echo "MOVIE_FFMPEG=$FILE_FFMPEG_LIST" >> $FILE_MOVIE_VARS
    echo "MOVIE_OUTPUT=$OUTPUT$MOVIE_NAME.mp4" >> $FILE_MOVIE_VARS
    echo "MOVIE_SUBTITLES=$MOVIE_SUBTITLES" >> $FILE_MOVIE_VARS
    
    # Download playlist and extract only segments links.
    # Use curl of headers are present.
    if [[ $playlist_url =~ ^file:// ]]; then
        LIST=$(curl $playlist_url | grep $PARSE_SEGMENTS )
    else
      wget $playlist_url --output-document=pls.file --no-verbose
      LIST=$(grep $PARSE_SEGMENTS pls.file)
      rm pls.file
    fi

    for f in $LIST;
    do
        if [ $USE_FULL_PATH -eq 1 ]; then
            NEW_FILE=$(basename $f | cut -d'?' -f1)
            echo "file '$OUTPUT_MOVIE_SEGMENTS/$NEW_FILE'" >> $FILE_FFMPEG_LIST
            echo "$f" >> $FILE_WGET_LIST
        else
            echo "file '$OUTPUT_MOVIE_SEGMENTS/$f'" >> $FILE_FFMPEG_LIST
            echo "$VIDEO_FOLDER$f" >> $FILE_WGET_LIST
        fi
        
        echo "$OUTPUT_MOVIE_SEGMENTS" >> $FILE_WGET_DEST
    done
    echo "Saving lists files done."
}

# Download segments and create final movie file on success.
segments_download() {
    if test ! -f "$FILE_WGET_LIST"; then
        echo "No previous segments found."
        return
    fi
    
    # If counter file already present we shuld continue downloading.
    COUNTER=0
    if test -f "$FILE_COUNTER"; then
       COUNTER=$(<"$FILE_COUNTER")
       echo "Continue downloading from $COUNTER ..."
    else
        # Create counter file.
        echo 0 > $FILE_COUNTER
    fi
    TOTAL_FILES=$(sed -n '$=' $FILE_WGET_LIST)
    readarray -t FILE_LIST < $FILE_WGET_LIST
    readarray -t FILE_DEST < $FILE_WGET_DEST

    DESTINATION_MOVIE_NAME=$(echo "${FILE_DEST[0]}" | awk -F'/' '{print $NF}')
    # Headers files should be handled by providers.
    FILE_HEADERS="$VARS_DIR/$DESTINATION_MOVIE_NAME.headers"
    FILE_KEY="$VARS_DIR/$DESTINATION_MOVIE_NAME.key"
    KEY_HEX=""
    # Load headers if present
    if test -f "$FILE_HEADERS"; then
      echo "Header file found!!"
      curl_headers=$(cat "$FILE_HEADERS")
    fi

    if test -f "$FILE_KEY"; then
      KEY_HEX=$(od -tx1 -An -N 16 "$FILE_KEY" | tr -d ' ')
    fi

    for (( i=$(($COUNTER));i<$(($TOTAL_FILES));i++)); do
        DOWNLOADED_FILE=$(basename ${FILE_LIST[${i}]} | cut -d'?' -f1)
        DESTINATION_FOLDER=${FILE_DEST[${i}]}

        if test -f "$FILE_HEADERS"; then
          # echo "Downloading file to "${DESTINATION_FOLDER}/${DOWNLOADED_FILE}""
          # Equivalent curl command with wget-like features
          local cmd="curl -L -s -S ${curl_headers} --retry 5 --retry-delay 1 --connect-timeout 15 --max-time 20 --create-dirs -o "${DESTINATION_FOLDER}/${DOWNLOADED_FILE}" '${FILE_LIST[${i}]}'"
          eval "$cmd"
        else
          # This will retry refused connections and similar fatal errors (--retry-connrefused), 
          # it will wait 1 second before next retry (--waitretry), it will wait a maximum of
          # 20 seconds in case no data is received and then try again (--read-timeout),
          # it will wait max 15 seconds before the initial connection times out (--timeout) 
          # and finally it will retry a 2 number of times (-t 2).
          wget --continue --quiet --retry-connrefused --waitretry=1 --read-timeout=20 --timeout=15 --no-verbose -t 5 --directory-prefix=${FILE_DEST[${i}]} ${FILE_LIST[${i}]} 
        fi
        
        # Halt if segment was not available.
        if [ ! -z "${FILE_LIST[${i}]}" ]; then
            if test ! -f "${FILE_DEST[${i}]}/$DOWNLOADED_FILE"; then
                # todo: restart script after some time.
                echo "!! Error downloading segment. pls restart. !!"
                exit
            else
              # Videos could be encoded.
              if [ ! -z "${KEY_HEX}" ]; then
                # echo "Key file found! Decoding video."
                local KEY_IV=$(od -tx1 -An -N 16 "${DESTINATION_FOLDER}/${DOWNLOADED_FILE}" | tr -d ' ')
                openssl enc -d -aes-128-cbc -K ${KEY_HEX} -iv ${KEY_IV} -in "${DESTINATION_FOLDER}/${DOWNLOADED_FILE}" -out "${DESTINATION_FOLDER}/${DOWNLOADED_FILE}e"
                rm ${DESTINATION_FOLDER}/${DOWNLOADED_FILE}
                mv ${DESTINATION_FOLDER}/${DOWNLOADED_FILE}e ${DESTINATION_FOLDER}/${DOWNLOADED_FILE}
              fi 
            fi
        fi
        echo "Progress: $((i+1)) / $TOTAL_FILES"
        echo $i > $FILE_COUNTER
    done
    
    echo "Download segments finished."
    # Get all movies vars files.
    MOVIES_LIST=$(find $VARS_DIR \( -name '*.vars' \) -type f -print | sort -R )
    for movie_vars in $MOVIES_LIST
    do
        # Load variables per movie
        . "$movie_vars"

        FFMPEG_INPUT="-i $MOVIE_FFMPEG -c copy "
        FFMPEG_MAP=" -map 0 "
        FFMPEG_SUBTITLES="-c:s mov_text "

        # Check if subtitles should be added.
        if [ ! -z $MOVIE_SUBTITLES ]; then
          # Extract names
          readarray -t names < <(echo "$MOVIE_SUBTITLES" | sed 's/",$//' | grep -oP '\[\K[^\]]*' | sed 's/\]//')

          # Extract links  
          readarray -t links < <(echo "$MOVIE_SUBTITLES" | sed 's/",$//' | grep -oP '\][^,\[]*' | sed 's/^]//')

          # Download subtitles and convert them.
          for i in "${!links[@]}"; do
              SUBTITLE_LINK="${links[i]}"
              SUBTITLE_NAME=$(basename $SUBTITLE_LINK)
              echo "Download subtitle: $SUBTITLE_NAME"
              wget $SUBTITLE_LINK --output-document=$MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME --no-verbose
              # If subtitle was downloaded convert it to srt
              # and add to the processing.
              if [ -f $MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME ]; then
                ffmpeg -y -hide_banner -i $MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME -c:s subrip $MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME.srt
                FFMPEG_SUBTITLES="$FFMPEG_SUBTITLES -metadata:s:s:$i language=${names[i]} "
                FFMPEG_INPUT="$FFMPEG_INPUT -i $MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME.srt "
                FFMPEG_MAP="$FFMPEG_MAP -map $((i+1))"

                # Subtitles are out of sync because of 23.976 framerate.
                # Try to convert if python3 is available.
                if command -v python3 >/dev/null 2>&1
                then
                  python3 ./scripts/srt-timecode-drift.py $MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME.srt $MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME.srt_2 -0.014
                  rm $MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME.srt
                  mv $MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME.srt_2 $MOVIE_FOLDER_SEGMENTS$SUBTITLE_NAME.srt
                fi
              fi
          done
        fi

        ffmpeg -hide_banner -y -f concat -safe 0 $FFMPEG_INPUT $FFMPEG_MAP $FFMPEG_SUBTITLES -bsf:a aac_adtstoasc $MOVIE_OUTPUT
        rm -rf $MOVIE_FFMPEG
        rm -rf $MOVIE_FOLDER_SEGMENTS
        rm -rf $movie_vars
        if test -f "$FILE_HEADERS"; then
          rm -rf $FILE_HEADERS
        fi
        if test -f "$FILE_KEY"; then
          rm -rf $FILE_KEY
        fi
    done
    
    segments_remove_tmp_files
    echo "$PROGRAM_NAME finished."
    exit
}

segments_remove_tmp_files() {
    # Remove all temp files and folders.
    if test -f "$FILE_COUNTER"; then
       rm $FILE_COUNTER
    fi
    if test -f "$FILE_WGET_LIST"; then
       rm $FILE_WGET_LIST
    fi
    if test -f "$FILE_WGET_DEST"; then
       rm $FILE_WGET_DEST
    fi

    echo "All temp files removed."
}

# 1 - url
# 2 - headers string
curl_request() {
  local url="$1"
  local header_args="$2"
  local cmd="curl -L -s ${header_args} '${url}'"
  eval "$cmd"
}

#================== START ==================
echo "$PROGRAM_NAME v.$VERSION is starting..."
PROVIDER_NAME=$(get_host $URL)
check_supported_provider

# Loading provider's custom scripts
. "$DIR/providers/$PROVIDER_NAME.sh"

segments_download

echo "url $URL"
echo "quality $QUALITY"
echo "playlist $PLAYLIST_NUM"
echo "season $SEASON"
echo "sound $SOUND"
echo "skip $SKIP"
echo "total $TOTAL"

segments_remove_tmp_files
init_segments_lists

segments_download

echo "$PROGRAM_NAME finished."

exit 
