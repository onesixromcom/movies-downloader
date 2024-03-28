# Universal Movies Downloader

This script was created to download videos from website uaserials.pro, uakino.club in different quality to watch them without ads on TV from USB drive.
It was at times when russians attack Ukraine and we were sitting without electrictiy and internet, so the only way to watch cartoons and movies was to download them from free streaming services.

By default wget queue will be created and after downloading all segments videos will be converted to mp4 with ffmpeg.
Program will exit if remote server is not responding after 5 reconnections. Run the same script again and downloading of segments will be resumed.

##### Installs needed before using the script:
`sudo apt install html-xml-utils wget ffmpeg npm`

`cd scripts; npm i`

## Usage
Download movie with default quality

`./movie.sh https://uaserials.pro/2042-velykyi-kush.html`

Download 10 episodes starting skipping 99 from the playlist.

`./movie.sh https://uakino.club/animeukr/anime-series/13232-van-ps-velikiy-kush-1-sezon.html --skip=99 --total=10`

### Params
`--season=N`
Specific season for show.

`--sound=N`
Set Audio track.

`--quality=N`
Quality: 480, 720, 1080 if available

`dry-run`
Will create all files needed for queue download or check if movie is available for download in case of using ffmepg downloader.

`--output=PATH`
Set folder to download movie.
Default is /home/$USER/Videos/movies

`--use-ffmpeg=1`
Switch to ffmpeg downloader. Could be the issue when one of the segment goes timeout. Download will stuck and will be started from the start next run.

`--skip=N`
Skip first N videos from season.

`--total=N`
Total videos to be downloaded if episodes are available.

`--playlist=1`
Useful for uakino.club when there are more than 1 season playlists.
