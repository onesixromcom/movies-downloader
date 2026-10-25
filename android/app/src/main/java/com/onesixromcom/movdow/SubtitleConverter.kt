package com.onesixromcom.movdow

/** WebVTT -> SRT, the format TVs read from a USB drive. */
object SubtitleConverter {

    private val TIME_REGEX = Regex("""([\d:.]+)\s*-->\s*([\d:.]+)""")

    fun vttToSrt(vtt: String): String {
        val out = StringBuilder()
        var number = 0
        for (block in vtt.replace("\r", "").split(Regex("\n{2,}"))) {
            val lines = block.lines().filter { it.isNotBlank() }
            val timeLine = lines.indexOfFirst { it.contains("-->") }
            if (timeLine < 0) continue
            val time = TIME_REGEX.find(lines[timeLine]) ?: continue
            number++
            out.append(number).append('\n')
                .append(srtTime(time.groupValues[1])).append(" --> ").append(srtTime(time.groupValues[2])).append('\n')
            lines.drop(timeLine + 1).forEach { out.append(it).append('\n') }
            out.append('\n')
        }
        return out.toString()
    }

    // 01:02.500 -> 00:01:02,500
    private fun srtTime(time: String): String {
        val full = if (time.count { it == ':' } == 1) "00:$time" else time
        return full.replace('.', ',')
    }
}
