# Universal Movies Downloader

This script was created to download videos from website uaserials.pro, uakino.club in different quality to watch them without ads on TV from USB drive.
It was at times when russians attack Ukraine and we were sitting without electrictiy and internet, so the only way to watch cartoons and movies was to download them from free streaming services.

By default wget queue will be created and after downloading all segments videos will be converted to mp4 with ffmpeg.
Program will exit if remote server is not responding after 5 reconnections. Run the same script again and downloading of segments will be resumed.

##### Installs needed before using the script:
```sh
sudo apt install html-xml-utils wget ffmpeg npm
cd scripts; npm i`
```

## Usage
Download movie with default quality

```sh
./movie.sh https://uaserials.pro/2042-velykyi-kush.html`
```

Download 10 episodes starting skipping 99 from the playlist.

```sh
./movie.sh https://uakino.club/animeukr/anime-series/13232-van-ps-velikiy-kush-1-sezon.html --skip=99 --total=10
```

```sh
OPTIONS:
  --season=N        Specific season for show.
  --sound=N         Set Audio track.
  --quality=N       Quality: 480, 720, 1080 if available
  --dry-run         Will create all files needed for queue download or check if movie is available for download in case of using ffmepg downloader.
  --output=PATH     Set folder to download movie. Default is /home/$USER/Videos/movies
  --use-ffmpeg      Switch to ffmpeg downloader. Could be the issue when one of the segment goes timeout. Download will stuck and will be started from the start next run.
  --skip=N          Skip first N videos from season.
  --total=N         Total videos to be downloaded if episodes are available.
  --playlist=1      Useful for uakino.club when there are more than 1 season playlists.
```

## Android app: MovDow

The `android/` folder contains **MovDow**, a Kotlin app (package `com.onesixromcom.movdow`). Supported websites: **uakino.best**, **kinoukr.tv**, **uafix.net**, **uaserials.com**.
Players: ashdi.vip, zetvideo.net, tortuga.tw (single videos and series playlists with voices and seasons).

There are no curl-impersonate, html-xml-utils, node or ffmpeg dependencies on Android:
- pages and the `playlists.php` ajax request are loaded by a hidden WebView (real Chromium, so Cloudflare passes);
- HTML is parsed with Jsoup;
- iframe, m3u8 playlists and `.ts` segments are requested with OkHttp (DNS-over-HTTPS, so blocked hosts like ashdi.vip resolve);
- segments are joined and converted to `.mp4` with Android's MediaExtractor/MediaCodec/MediaMuxer. With **Fix audio** (default) it does the same as
  `ffmpeg -i in.ts -map 0 -c:v copy -c:a aac -b:a 128k -af "aresample=async=1:first_pts=0" -avoid_negative_ts make_zero -movflags +faststart out.mp4`:
  video is copied, audio is decoded, gaps between segments are filled with silence (overlaps are cut), encoded to AAC 128 kbps, timestamps start at 0 and the mp4 index is moved to the front.
  If that fails, a plain remux is tried, then the `.ts` file is saved;
- `.vtt` subtitles are converted to `.srt` and saved next to the video.

### Requirements
- JDK 17 or newer
- Android SDK with platform 35 (Android Studio installs it, or use `sdkmanager "platforms;android-35" "build-tools;35.0.0"`)

Gradle is downloaded by the wrapper, no need to install it.

### Usage
First open **Settings** and choose the download folder.

**Add** tab
- In the browser open a movie or series page on a supported website, tap **Share** and choose **MovDow**, or paste the URL and tap **Process**.
- Choose the voice and the quality. For series, select the episodes (or **All**), then tap **Add to queue**.

**Queue** tab
- Shows the video being downloaded (segments progress) and the waiting ones.
- **Pause** / **Resume** the queue. Downloaded segments are kept, so the download continues where it stopped, also after the app restart.
- **Remove** an item or **Remove all** (with confirmation). Saved videos stay in the folder, unfinished downloads are deleted.
- Failed items can be retried.

Downloads run in a foreground service with a progress notification, so they continue with the screen off.

**Settings** tab
- Download folder.
- Folder per series: episodes go into a folder named after the show. Movies are saved into the download folder.
- Episode names without the show name (with folder per series): `Король Талси/Сезон 1 Серія 1.mp4` instead of `Король Талси/Король Талси - Сезон 1 Серія 1.mp4`.
- Select the first voice automatically.
- Default quality (a lower one is used if it's not available).
- Download subtitles if the player has them.
- Transliterate file names: Latin letters (Ukrainian official transliteration), lowercase, `_` instead of spaces, e.g. `doskonala_zhurba.mp4`. On by default.
- Fix audio: re-encode audio so it stays in sync (adds a few minutes per movie). Turn it off to only copy the streams.

**About** tab: app version, link to the home page and the list of supported websites.

A log panel at the bottom of every tab shows what's going on (page loading, playlists, segments progress, audio fix, saved files). **Copy** copies the whole log.
