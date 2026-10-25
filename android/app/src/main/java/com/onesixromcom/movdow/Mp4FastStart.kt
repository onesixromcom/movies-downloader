package com.onesixromcom.movdow

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Channels

/**
 * ffmpeg -movflags +faststart: MediaMuxer writes the moov atom (the index) at the end of the file,
 * this moves it before mdat while copying, and shifts chunk offsets (stco/co64) by the moov size.
 * Port of qt-faststart.
 */
object Mp4FastStart {

    private class Atom(val type: String, val offset: Long, val size: Long)

    // Path from moov to stco/co64.
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl")
    private const val MAX_MOOV_SIZE = 256L * 1024 * 1024

    /** Copy [input] to [output] with moov in front. Returns false if the file was copied as is. */
    fun write(input: File, output: OutputStream): Boolean {
        RandomAccessFile(input, "r").use { file ->
            val atoms = topLevelAtoms(file)
            val moov = atoms?.firstOrNull { it.type == "moov" }
            val mdatIndex = atoms?.indexOfFirst { it.type == "mdat" } ?: -1
            val target = Channels.newChannel(output)

            if (atoms == null || moov == null || mdatIndex < 0 || atoms.indexOf(moov) < mdatIndex || moov.size > MAX_MOOV_SIZE) {
                copy(file, 0, file.length(), target)
                return false
            }

            val moovBytes = ByteArray(moov.size.toInt())
            file.seek(moov.offset)
            file.readFully(moovBytes)
            if (!shiftOffsets(ByteBuffer.wrap(moovBytes), 0, moovBytes.size, moov.size)) {
                copy(file, 0, file.length(), target)
                return false
            }

            // ftyp (and whatever is before mdat), moov, then the rest without the old moov.
            atoms.subList(0, mdatIndex).forEach { copy(file, it.offset, it.size, target) }
            output.write(moovBytes)
            atoms.subList(mdatIndex, atoms.size).filter { it !== moov }.forEach { copy(file, it.offset, it.size, target) }
            output.flush()
            return true
        }
    }

    private fun topLevelAtoms(file: RandomAccessFile): List<Atom>? {
        val atoms = mutableListOf<Atom>()
        val length = file.length()
        var position = 0L
        val type = ByteArray(4)
        while (position + 8 <= length) {
            file.seek(position)
            var size = file.readInt().toLong() and 0xffffffffL
            file.readFully(type)
            if (size == 1L) size = file.readLong()
            if (size == 0L) size = length - position
            if (size < 8 || position + size > length) return null
            atoms += Atom(String(type, Charsets.US_ASCII), position, size)
            position += size
        }
        return atoms
    }

    /** Add [delta] to every chunk offset. False if a 32-bit offset overflows. */
    private fun shiftOffsets(buffer: ByteBuffer, start: Int, end: Int, delta: Long): Boolean {
        var position = start
        while (position + 8 <= end) {
            var size = buffer.getInt(position).toLong() and 0xffffffffL
            val type = String(ByteArray(4) { buffer.get(position + 4 + it) }, Charsets.US_ASCII)
            var header = 8
            if (size == 1L) {
                size = buffer.getLong(position + 8)
                header = 16
            }
            if (size == 0L) size = (end - position).toLong()
            if (size < header || position + size > end) return false

            when (type) {
                in CONTAINERS -> if (!shiftOffsets(buffer, position + header, (position + size).toInt(), delta)) return false
                "stco" -> {
                    val count = buffer.getInt(position + header + 4)
                    for (i in 0 until count) {
                        val at = position + header + 8 + 4 * i
                        val value = (buffer.getInt(at).toLong() and 0xffffffffL) + delta
                        if (value > 0xffffffffL) return false
                        buffer.putInt(at, value.toInt())
                    }
                }
                "co64" -> {
                    val count = buffer.getInt(position + header + 4)
                    for (i in 0 until count) {
                        val at = position + header + 8 + 8 * i
                        buffer.putLong(at, buffer.getLong(at) + delta)
                    }
                }
            }
            position += size.toInt()
        }
        return true
    }

    private fun copy(file: RandomAccessFile, offset: Long, size: Long, target: java.nio.channels.WritableByteChannel) {
        val channel = file.channel
        var done = 0L
        while (done < size) {
            done += channel.transferTo(offset + done, size - done, target)
        }
    }
}
