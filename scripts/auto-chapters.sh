#/usr/bin/env bash

args=("$@") 
FILE_MOVIE=${args[0]}
FILE_META=${args[1]}

if [[ -z "$FILE_MOVIE" ]]; then
	echo "No movie file.".
	exit
fi

if [[ -z "$FILE_META" ]]; then
	echo "No meta file".
	exit
fi

MOVIE_LENGTH=$(ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 "$FILE_MOVIE")
MOVIE_LENGTH="${MOVIE_LENGTH%.*}"
MOVIE_LENGTH=$((MOVIE_LENGTH - 600))

if [[ "MOVIE_LENGTH" -lt 600 ]]; then
	"Movie too short. No chapters needed."
	exit
fi

COUNTER=1
echo -e "\n\n" >> $FILE_META
for (( i=0;i<$(($MOVIE_LENGTH));i+=600)); do
	START=$((i * 1000))
	END=$(((i+600)*1000 - 1))
	echo -e "[CHAPTER]\nTIMEBASE=1/1000\nSTART=$START\nEND=$END\ntitle=Chapter $COUNTER\n" >> $FILE_META
	COUNTER=$((COUNTER+1))
done
