package com.subflow.pipeline

/**
 * Checks a subtitle's real language from its cue text, since source language tags lie.
 * Script check first, then stopwords + distinctive chars for Latin languages. Offline.
 */
object LangDetect {

    private val stopwords: Map<String, Set<String>> = mapOf(
        "tr" to setOf(
            "bir", "ve", "bu", "ne", "için", "değil", "ama", "gibi", "çok", "daha",
            "ben", "sen", "biz", "evet", "hayır", "tamam", "şey", "var", "yok",
            "seni", "benim", "senin", "onu", "bana", "beni", "sana", "burada", "şimdi", "sadece"
        ),
        "en" to setOf(
            "the", "you", "and", "that", "what", "this", "have", "with", "not", "are",
            "was", "your", "just", "know", "here", "they", "will", "can't", "don't", "it's",
            "i'm", "about", "going", "want", "right"
        ),
        "de" to setOf(
            "der", "die", "das", "und", "nicht", "ich", "ist", "du", "wir", "sie",
            "ein", "eine", "mit", "was", "haben", "wird", "aber", "auch", "für", "noch",
            "dich", "mir", "hier", "wenn", "schon"
        ),
        "es" to setOf(
            "que", "el", "la", "es", "no", "por", "los", "una", "para", "está",
            "pero", "como", "más", "bien", "aquí", "qué", "esto", "tengo", "puedo", "quiero",
            "usted", "ahora", "nada", "vamos", "hay"
        ),
        "fr" to setOf(
            "le", "la", "les", "est", "pas", "je", "que", "vous", "nous", "une",
            "pour", "avec", "mais", "c'est", "tout", "bien", "ici", "quoi", "suis", "fait",
            "être", "faire", "moi", "toi", "rien"
        ),
        "it" to setOf(
            "che", "di", "il", "non", "è", "per", "una", "sono", "con", "come",
            "questo", "hai", "cosa", "bene", "qui", "voglio", "posso", "fatto", "essere", "anche",
            "della", "lei", "lui", "noi", "adesso"
        ),
        "pt" to setOf(
            "que", "não", "uma", "para", "está", "com", "você", "isso", "aqui", "bem",
            "como", "mais", "vamos", "quero", "posso", "fazer", "ele", "ela", "nós", "agora",
            "muito", "tudo", "então", "coisa", "obrigado"
        ),
        "nl" to setOf(
            "het", "een", "niet", "ik", "dat", "van", "je", "voor", "met", "zijn",
            "maar", "wat", "hier", "goed", "kan", "moet", "gaan", "weet", "deze", "over"
        ),
        "pl" to setOf(
            "nie", "jest", "się", "tak", "ale", "jak", "czy", "tego", "być", "mam",
            "już", "tutaj", "dobrze", "teraz", "wiem", "chcę", "mogę", "przez", "tylko", "jeszcze"
        ),
        "id" to setOf(
            "yang", "tidak", "aku", "kamu", "ini", "itu", "dan", "akan", "untuk", "dengan",
            "bisa", "sudah", "apa", "ada", "kita", "saya", "baik", "sekarang", "harus", "mereka"
        )
    )

    /** distinctive chars per Latin language */
    private val distinctiveChars: Map<String, String> = mapOf(
        // 'İ' left out on purpose. lowercase() turns it into two chars so it never matches
        "tr" to "ğış",
        "de" to "ß",
        "es" to "ñ¿¡",
        "pt" to "ãõ",
        "pl" to "łżźćńśę",
        "fr" to "êëîïœ"
    )

    /** returns an ISO 639-1 code, or null when unsure */
    fun detect(srtContent: String): String? {
        val text = extractCueText(srtContent)
        if (text.length < 80) return null

        // script-based detection
        var arabic = 0; var cyrillic = 0; var kana = 0; var hangul = 0; var cjk = 0; var latin = 0
        for (c in text) {
            when (c.code) {
                in 0x0600..0x06FF, in 0x0750..0x077F -> arabic++
                in 0x0400..0x04FF -> cyrillic++
                in 0x3040..0x30FF -> kana++
                in 0xAC00..0xD7AF -> hangul++
                in 0x4E00..0x9FFF -> cjk++
                in 0x0041..0x024F -> latin++
            }
        }
        val scripted = arabic + cyrillic + kana + hangul + cjk
        if (scripted > latin / 2 && scripted > 40) {
            return when {
                arabic >= maxOf(cyrillic, kana, hangul, cjk) -> "ar"
                cyrillic >= maxOf(kana, hangul, cjk) -> "ru"
                kana >= maxOf(hangul, cjk) -> "ja"
                hangul >= cjk -> "ko"
                else -> "zh"
            }
        }
        if (latin < 60) return null

        // latin: stopword frequency + distinctive chars
        val lower = text.replace('İ', 'i').lowercase()
        val words = lower.split(Regex("[^\\p{L}']+")).filter { it.length > 1 }
        if (words.size < 20) return null
        val wordCounts = words.groupingBy { it }.eachCount()

        val scores = HashMap<String, Double>()
        for ((lang, stops) in stopwords) {
            var hits = 0
            for (w in stops) hits += wordCounts[w] ?: 0
            var score = hits.toDouble() / words.size
            distinctiveChars[lang]?.let { chars ->
                val charHits = lower.count { it in chars }
                score += (charHits.toDouble() / text.length) * 8.0
            }
            scores[lang] = score
        }

        val sorted = scores.entries.sortedByDescending { it.value }
        val best = sorted[0]
        val second = sorted[1]
        // strong enough and clear of the runner-up
        if (best.value < 0.02) return null
        if (second.value > 0 && best.value < second.value * 1.4) return null
        return best.key
    }

    /** spoken text only, first ~120 cues */
    private fun extractCueText(srt: String): String {
        val sb = StringBuilder()
        var cues = 0
        for (line in srt.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) { cues++; if (cues > 120) break; continue }
            if (t.contains("-->")) continue
            if (t.all { it.isDigit() }) continue
            sb.append(t.replace(Regex("<[^>]+>"), "")).append(' ')
            if (sb.length > 6000) break
        }
        return sb.toString()
    }
}
