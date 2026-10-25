package com.onesixromcom.movdow

import java.text.Normalizer

/**
 * File names in Latin letters: Ukrainian official transliteration (2010), lowercase,
 * spaces and other symbols -> "_".
 * "Людина-павук: Абсолютно новий день" -> "liudyna-pavuk_absoliutno_novyi_den"
 */
object Transliteration {

    private val LETTERS = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'ґ' to "g", 'д' to "d", 'е' to "e", 'є' to "ie",
        'ж' to "zh", 'з' to "z", 'и' to "y", 'і' to "i", 'ї' to "i", 'й' to "i", 'к' to "k", 'л' to "l",
        'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ь' to "", 'ю' to "iu",
        'я' to "ia", '\'' to "", '’' to "", 'ʼ' to "",
        // Russian letters that may appear in titles.
        'ё' to "e", 'ы' to "y", 'э' to "e", 'ъ' to "",
    )

    // At the beginning of a word: Єнакієве -> Yenakiieve, Їжак -> Yizhak, Юрій -> Yurii, Яготин -> Yahotyn.
    private val WORD_START = mapOf('є' to "ye", 'ї' to "yi", 'й' to "y", 'ю' to "yu", 'я' to "ya")

    private val COMBINING_MARKS = Regex("\\p{Mn}+")
    private val NOT_ALLOWED = Regex("[^a-z0-9-]+")
    private val DASHES = Regex("_*-[_-]*")

    fun latin(text: String): String {
        val lower = text.lowercase()
        val out = StringBuilder()
        lower.forEachIndexed { i, c ->
            val previous = lower.getOrNull(i - 1)
            val wordStart = previous == null || !(previous.isLetter() || previous in "'’ʼ")
            out.append(
                when {
                    // Згорани -> Zghorany: "зг" is "zgh" so it is not read as "zh".
                    c == 'г' && previous == 'з' -> "gh"
                    wordStart && c in WORD_START -> WORD_START.getValue(c)
                    else -> LETTERS[c] ?: c.toString()
                },
            )
        }
        // Other accented letters: é -> e.
        return COMBINING_MARKS.replace(Normalizer.normalize(out, Normalizer.Form.NFD), "")
    }

    fun fileName(text: String): String =
        latin(text)
            .replace(NOT_ALLOWED, "_")
            .replace(DASHES, "-")
            .trim('_', '-')
            .take(120)
            .ifEmpty { "video" }
}
