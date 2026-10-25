package com.onesixromcom.movdow

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * .ts -> .mp4 without ffmpeg. With [fixAudio] it does the same as
 *
 *   ffmpeg -i in.ts -map 0 -c:v copy -c:a aac -b:a 128k -af "aresample=async=1:first_pts=0" \
 *     -avoid_negative_ts make_zero out.mp4
 *
 * (+faststart is done by [Mp4FastStart] while copying to the download folder):
 * - video is copied;
 * - audio is decoded to PCM, gaps between segments are filled with silence and overlaps are cut
 *   (so audio stays in sync with video), then encoded to AAC;
 * - timestamps start at 0.
 * Without [fixAudio] both tracks are copied, only timestamps are shifted to 0.
 */
class Remuxer(
    private val fixAudio: Boolean,
    private val audioBitrate: Int = 128_000,
    /** Called with the processed audio position, for progress. */
    private val onAudioProgress: (positionUs: Long) -> Unit = {},
) {

    private companion object {
        // Encoder input chunk: many AAC frames per buffer.
        const val ENCODER_CHUNK = 32 * 1024L

        // Like ffmpeg aresample min_hard_comp: smaller deviations are ignored.
        const val HARD_COMP_US = 100_000L

        // A bigger jump is a timestamp discontinuity, not a gap to fill with silence.
        const val MAX_GAP_US = 30_000_000L
        const val MAX_PENDING_PCM = 1 shl 20
    }

    suspend fun remux(input: File, output: File, workDir: File) {
        val probe = MediaExtractor().apply { setDataSource(input.path) }
        var videoTrack: Int? = null
        var audioTrack: Int? = null
        for (i in 0 until probe.trackCount) {
            val mime = probe.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (videoTrack == null && mime.startsWith("video/")) videoTrack = i
            if (audioTrack == null && mime.startsWith("audio/")) audioTrack = i
        }
        probe.release()
        if (videoTrack == null && audioTrack == null) throw IOException("No audio/video tracks")

        val baseUs = startTimeUs(input, listOfNotNull(videoTrack, audioTrack))
        AppLog.i("Remux: start time ${baseUs / 1000} ms, video track $videoTrack, audio track $audioTrack, fix audio: $fixAudio")

        val encoded = if (fixAudio && audioTrack != null) {
            encodeAudio(input, audioTrack, baseUs, File(workDir, "audio.aac"))
        } else {
            null
        }
        try {
            mux(input, output, videoTrack, if (encoded == null) audioTrack else null, encoded, baseUs)
        } finally {
            encoded?.file?.delete()
        }
    }

    /** The earliest timestamp (pts of the first frames; video may have B-frames, so look at a few). */
    private fun startTimeUs(input: File, tracks: List<Int>): Long {
        var min = Long.MAX_VALUE
        for (track in tracks) {
            val extractor = MediaExtractor().apply { setDataSource(input.path) }
            extractor.selectTrack(track)
            repeat(60) {
                if (extractor.sampleTime >= 0) min = minOf(min, extractor.sampleTime)
                if (!extractor.advance()) return@repeat
            }
            extractor.release()
        }
        return if (min == Long.MAX_VALUE) 0 else min
    }

    private class EncodedAudio(val format: MediaFormat, val file: File, val frames: FrameIndex)

    /** Encoded AAC frames stored in a file: timestamp, size and flags of every frame. */
    private class FrameIndex {
        var pts = LongArray(4096)
        var sizes = IntArray(4096)
        var flags = IntArray(4096)
        var count = 0

        fun add(timeUs: Long, size: Int, flag: Int) {
            if (count == pts.size) {
                pts = pts.copyOf(count * 2)
                sizes = sizes.copyOf(count * 2)
                flags = flags.copyOf(count * 2)
            }
            pts[count] = timeUs
            sizes[count] = size
            flags[count] = flag
            count++
        }
    }

    /** PCM waiting for the encoder. */
    private class PcmQueue {
        private val chunks = ArrayDeque<ByteArray>()
        private var headOffset = 0
        var size = 0L
            private set
        var endOfStream = false

        fun add(buffer: ByteBuffer) {
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            if (bytes.isEmpty()) return
            chunks.addLast(bytes)
            size += bytes.size
        }

        fun addSilence(bytes: Long) {
            var left = bytes
            while (left > 0) {
                val n = minOf(left, 64L * 1024).toInt()
                chunks.addLast(ByteArray(n))
                size += n
                left -= n
            }
        }

        /** Fill [target] with whole PCM frames. */
        fun read(target: ByteBuffer, frameBytes: Int): Int {
            var want = minOf(target.remaining().toLong(), size).toInt()
            want -= want % frameBytes
            var written = 0
            while (written < want) {
                val head = chunks.first()
                val n = minOf(head.size - headOffset, want - written)
                target.put(head, headOffset, n)
                headOffset += n
                written += n
                if (headOffset == head.size) {
                    chunks.removeFirst()
                    headOffset = 0
                }
            }
            size -= written
            return written
        }
    }

    // Decode -> fix gaps (aresample=async=1:first_pts=0) -> encode AAC.
    private suspend fun encodeAudio(input: File, track: Int, baseUs: Long, output: File): EncodedAudio {
        val extractor = MediaExtractor().apply { setDataSource(input.path) }
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        var encoder: MediaCodec? = null
        var encoderFormat: MediaFormat? = null
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = PcmQueue()
        val frames = FrameIndex()
        val info = MediaCodec.BufferInfo()

        // Position of the output timeline and of the encoder input, in PCM frames.
        var writtenFrames = 0L
        var encodedFrames = 0L
        var silenceUs = 0L
        var droppedUs = 0L
        var lastProgressUs = 0L

        var inputDone = false
        var decoderDone = false
        var encoderInputDone = false
        var encoderDone = false
        var outBytes = ByteArray(8 * 1024)
        val startedAt = System.currentTimeMillis()

        // Codecs are polled without waiting and every ready buffer is handled at once;
        // waiting only happens when neither codec had anything to do.
        try {
            output.outputStream().buffered(256 * 1024).use { out ->
                while (!encoderDone) {
                    coroutineContext.ensureActive()
                    var progress = false

                    while (!inputDone) {
                        val index = decoder.dequeueInputBuffer(0)
                        if (index < 0) break
                        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        progress = true
                    }

                    while (!decoderDone && pcm.size < MAX_PENDING_PCM) {
                        val index = decoder.dequeueOutputBuffer(info, 0)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            continue
                        }
                        if (index < 0) break
                        progress = true
                        if (encoder == null) {
                            encoder = createEncoder(sampleRate, channels)
                            AppLog.i("Audio: ${sampleRate} Hz, $channels ch -> AAC ${audioBitrate / 1000} kbps")
                        }
                        val frameBytes = 2 * channels
                        val buffer = decoder.getOutputBuffer(index)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (info.size > 0) {
                            val expected = (info.presentationTimeUs - baseUs) * sampleRate / 1_000_000
                            val diff = expected - writtenFrames
                            val diffUs = diff * 1_000_000 / sampleRate
                            when {
                                diffUs > MAX_GAP_US || diffUs < -MAX_GAP_US ->
                                    AppLog.e("Audio: timestamp jump of ${diffUs / 1000} ms, not compensated")
                                diffUs > HARD_COMP_US -> {
                                    pcm.addSilence(diff * frameBytes)
                                    writtenFrames += diff
                                    silenceUs += diffUs
                                }
                                diffUs < -HARD_COMP_US -> {
                                    val drop = minOf(-diff, (info.size / frameBytes).toLong())
                                    buffer.position(buffer.position() + (drop * frameBytes).toInt())
                                    droppedUs += drop * 1_000_000 / sampleRate
                                }
                            }
                            writtenFrames += buffer.remaining() / frameBytes
                            pcm.add(buffer)
                        }
                        decoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            decoderDone = true
                            pcm.endOfStream = true
                        }
                    }

                    val enc = encoder
                    if (enc == null) {
                        if (decoderDone) throw IOException("No audio decoded")
                        if (!progress) delay(1)
                        continue
                    }

                    // Feed the encoder with full buffers (many AAC frames each), not one decoded chunk at a time.
                    while (!encoderInputDone && (pcm.size >= ENCODER_CHUNK || pcm.endOfStream)) {
                        val index = enc.dequeueInputBuffer(0)
                        if (index < 0) break
                        val buffer = enc.getInputBuffer(index)!!
                        buffer.clear()
                        val n = pcm.read(buffer, 2 * channels)
                        val pts = encodedFrames * 1_000_000 / sampleRate
                        encodedFrames += n / (2 * channels)
                        val last = pcm.endOfStream && pcm.size == 0L
                        enc.queueInputBuffer(index, 0, n, pts, if (last) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                        if (last) encoderInputDone = true
                        progress = true
                    }

                    while (!encoderDone) {
                        val index = enc.dequeueOutputBuffer(info, 0)
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            encoderFormat = enc.outputFormat
                            continue
                        }
                        if (index < 0) break
                        progress = true
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val buffer = enc.getOutputBuffer(index)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            if (outBytes.size < info.size) outBytes = ByteArray(info.size)
                            buffer.get(outBytes, 0, info.size)
                            out.write(outBytes, 0, info.size)
                            frames.add(info.presentationTimeUs, info.size, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                            if (info.presentationTimeUs - lastProgressUs > 5_000_000) {
                                lastProgressUs = info.presentationTimeUs
                                onAudioProgress(info.presentationTimeUs)
                            }
                        }
                        enc.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }

                    // Both codecs are busy: wait a bit instead of spinning.
                    if (!progress && !encoderDone) delay(1)
                }
            }
        } finally {
            extractor.release()
            decoder.release()
            encoder?.release()
        }

        AppLog.i(
            "Audio fixed in ${(System.currentTimeMillis() - startedAt) / 1000} s: ${frames.count} AAC frames, " +
                "silence added ${silenceUs / 1000} ms, overlap cut ${droppedUs / 1000} ms",
        )
        return EncodedAudio(encoderFormat ?: throw IOException("AAC encoder gave no format"), output, frames)
    }

    private fun createEncoder(sampleRate: Int, channels: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, audioBitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    /** Write video (copied) + audio (encoded frames or copied) into mp4, timestamps shifted by [baseUs]. */
    private suspend fun mux(
        input: File,
        output: File,
        videoTrack: Int?,
        copyAudioTrack: Int?,
        encoded: EncodedAudio?,
        baseUs: Long,
    ) {
        val extractor = MediaExtractor().apply { setDataSource(input.path) }
        val muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val tracks = HashMap<Int, Int>()
            for (track in listOfNotNull(videoTrack, copyAudioTrack)) {
                extractor.selectTrack(track)
                tracks[track] = muxer.addTrack(extractor.getTrackFormat(track))
            }
            val encodedTrack = encoded?.let { muxer.addTrack(it.format) }
            muxer.start()

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val audioIn = encoded?.file?.inputStream()?.buffered(256 * 1024)
            // Separate from [buffer]: audio is written while a video sample is waiting in it.
            var audioBuffer = ByteBuffer.allocate(64 * 1024)
            val audioInfo = MediaCodec.BufferInfo()
            var audioIndex = 0

            // Interleave encoded audio frames with video by time.
            fun writeEncodedUntil(timeUs: Long) {
                val frames = encoded?.frames ?: return
                while (audioIndex < frames.count && frames.pts[audioIndex] <= timeUs) {
                    val size = frames.sizes[audioIndex]
                    if (audioBuffer.capacity() < size) audioBuffer = ByteBuffer.allocate(size)
                    audioBuffer.clear()
                    var read = 0
                    while (read < size) {
                        val n = audioIn!!.read(audioBuffer.array(), read, size - read)
                        if (n < 0) throw IOException("Audio frames file is truncated")
                        read += n
                    }
                    audioBuffer.limit(size)
                    audioInfo.set(0, size, frames.pts[audioIndex], frames.flags[audioIndex])
                    muxer.writeSampleData(encodedTrack!!, audioBuffer, audioInfo)
                    audioIndex++
                }
            }

            audioIn.use {
                while (true) {
                    coroutineContext.ensureActive()
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val timeUs = (extractor.sampleTime - baseUs).coerceAtLeast(0)
                    writeEncodedUntil(timeUs)
                    val keyFrame = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    info.set(0, size, timeUs, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(tracks.getValue(extractor.sampleTrackIndex), buffer, info)
                    extractor.advance()
                }
                writeEncodedUntil(Long.MAX_VALUE)
            }
            muxer.stop()
        } finally {
            extractor.release()
            try {
                muxer.release()
            } catch (e: IllegalStateException) {
                // Not started.
            }
        }
    }
}
