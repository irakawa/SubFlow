package com.subflow.pipeline

/**
 * baked into the apk, no runtime api. keeps slang and profanity at original intensity.
 * anything MT softened gets hardened back with these equivalents.
 */
object SlangDictionary {

    // en to tr slang/profanity map. keys matched lowercase.
    val enToTr: Map<String, String> = mapOf(
        // profanity / insults
        "damn" to "kahretsin",
        "damn it" to "lanet olsun",
        "goddamn" to "lanet olası",
        "goddammit" to "hay sıçayım",
        "shit" to "bok",
        "holy shit" to "vay anasını",
        "bullshit" to "saçmalık",
        "no shit" to "hadi ya, öyle mi",
        "piece of shit" to "bok parçası",
        "shithead" to "bok kafalı",
        "crap" to "boktan",
        "what the hell" to "ne oluyor be",
        "what the fuck" to "bu ne lan",
        "fuck" to "siktir",
        "fuck you" to "siktir git",
        "fuck off" to "siktir ol git",
        "fucking" to "sikik",
        "motherfucker" to "şerefsiz",
        "son of a bitch" to "orospu çocuğu",
        "bitch" to "kaltak",
        "bastard" to "piç",
        "asshole" to "göt herif",
        "ass" to "göt",
        "dumbass" to "gerizekalı",
        "jackass" to "andaval",
        "jerk" to "pislik",
        "idiot" to "salak",
        "moron" to "mal",
        "loser" to "ezik",
        "scumbag" to "it herif",
        "prick" to "yavşak",
        "douchebag" to "dallama",
        "screw you" to "canın cehenneme",
        "go to hell" to "cehenneme git",
        "shut up" to "kes sesini",
        "shut the fuck up" to "kapa çeneni lan",
        "piss off" to "defol",
        "pissed off" to "gıcık olmuş",
        "pissed" to "zıvanadan çıkmış",
        // slang / everyday
        "dude" to "kanka",
        "bro" to "birader",
        "buddy" to "ahbap",
        "man" to "dostum",
        "chill" to "sakin ol",
        "chill out" to "rahatla be",
        "no way" to "yok artık",
        "for real" to "ciddi misin",
        "seriously" to "cidden",
        "awesome" to "efsane",
        "badass" to "belalı",
        "creep" to "sapık",
        "creepy" to "ürkütücü",
        "gross" to "iğrenç",
        "sucks" to "berbat",
        "it sucks" to "boktan bir durum",
        "screwed" to "hapı yuttuk",
        "we're screwed" to "işimiz bitik",
        "busted" to "yakalandın",
        "hell no" to "asla olmaz",
        "hell yeah" to "aynen öyle lan",
        "damn right" to "hem de nasıl",
        "big deal" to "aman ne önemli",
        "whatever" to "her neyse",
        "knock it off" to "kes şunu",
        "cut the crap" to "zırvalamayı bırak",
        "get lost" to "kaybol",
        "you wish" to "rüyanda görürsün",
        "in your dreams" to "ancak rüyanda",
        "bite me" to "gel de ısır beni",
        "kiss my ass" to "götümü öp",
        "my ass" to "kıçımın kenarı",
        "smartass" to "ukala dümbeleği",
        "wise guy" to "çokbilmiş",
        "punk" to "serseri",
        "thug" to "kabadayı",
        "snitch" to "ispiyoncu",
        "rat" to "gammaz",
        "coward" to "korkak",
        "wimp" to "süt kuzusu",
        "nerd" to "inek",
        "geek" to "tiki",
        "freak" to "ucube",
        "psycho" to "psikopat",
        "lunatic" to "kaçık",
        "nuts" to "kafayı yemiş",
        "insane" to "manyak",
        "crazy" to "deli"
    )

    // jp romaji / anime jargon
    val jpToTr: Map<String, String> = mapOf(
        "baka" to "aptal",
        "kuso" to "kahretsin",
        "kisama" to "seni şerefsiz",
        "teme" to "seni piç",
        "urusai" to "kes sesini",
        "chikushou" to "lanet olsun",
        "yamete" to "dur",
        "sugoi" to "müthiş",
        "senpai" to "senpai",       // jargon
        "kouhai" to "kouhai",
        "sensei" to "sensei",
        "onii-chan" to "abi",
        "onee-chan" to "abla",
        "itadakimasu" to "afiyet olsun",
        "nakama" to "yoldaş",
        "shinigami" to "ölüm tanrısı",
        "youkai" to "yokai",
        "jutsu" to "jutsu",
        "dattebayo" to "işte böyle"
    )

    // cn / donghua jargon
    val cnToTr: Map<String, String> = mapOf(
        "shifu" to "usta",
        "shixiong" to "kıdemli birader",
        "shidi" to "genç birader",
        "dao" to "dao",
        "qi" to "çi",
        "cultivation" to "irfan yolu",
        "immortal" to "ölümsüz",
        "sect" to "tarikat",
        "young master" to "genç efendi"
    )

    // soft markers. harsh source but one of these in the target means harden the line.
    // "lanet olsun" is deliberately absent: mega_dictionary.json picks it as the rendering
    // for "goddamn it", and rewriting it to "kahretsin" (its pick for the weaker plain
    // "damn") downgrades the line instead of hardening it. The dictionary is the single
    // authority for what a term renders as; this table only repairs what MT softened.
    val sanitizedMarkers: Map<String, String> = mapOf(
        "kahrolası" to "lanet olası",
        "aptal herif" to "göt herif",
        "kötü adam" to "şerefsiz",
        "hay aksi" to "hay sıçayım",
        "canın cehenneme seni" to "siktir git"
    )

    private val strongSourceTokens = listOf(
        "fuck", "shit", "bitch", "bastard", "asshole", "motherfucker",
        "goddamn", "son of a bitch", "prick", "piss", "kuso", "kisama", "teme"
    )

    fun sourceHasProfanity(sourceLine: String): Boolean {
        val lower = sourceLine.lowercase()
        return strongSourceTokens.any { lower.contains(it) }
    }

    // NOTE: there is deliberately no generic "weak token" list any more. It used to hold
    // "tanrım", "kahretsin", "lanet" and "aptal" — all of them renderings mega_dictionary
    // itself picks, so the repair treated its own correct output as damage. Worse, any
    // such token could be used as a slot for any source term, which is how
    // "Aman Tanrım, bu çok acıyor!" became "Aman sikik, ...". The only slots left are the
    // [sanitizedMarkers] keys, and each is bound to one specific term (see below).

    // harsh stems that take Turkish suffixes freely ("boktan", "orospunun", "lanetli"):
    // matched at a word start, suffix free to follow. "lanet" belongs here because it is
    // mega_dictionary's own rendering for "goddamn" — it is harsh output, never a soft
    // marker, which is the contradiction this list used to carry both ways.
    private val strongTargetStems = listOf(
        "siktir", "bok", "orospu", "piç", "kaltak", "şerefsiz", "yavşak", "sıçayım", "lanet"
    )

    // tokens that are also the opening of ordinary words: "göt" opens the whole "götür-"
    // verb, "lan" sits inside yalan/plan/kullanılan/varsayılan. Only a standalone word
    // counts. The cost is missing an inflected "götüne"; the benefit is that an innocent
    // line is never read as harsh, which used to shut the gate on the very lines this
    // repair exists for.
    private val strongTargetWords = listOf("lan", "göt")

    /** true when the line already carries harsh language of its own. */
    private fun hasHarshEquivalent(lowerLine: String): Boolean =
        strongTargetStems.any { TextMatch.wordStart(it).containsMatchIn(lowerLine) } ||
            strongTargetWords.any { TextMatch.wholeWord(it).containsMatchIn(lowerLine) }

    /**
     * sanitized = the source swore and the target carries no harsh equivalent.
     *
     * Deliberately does NOT require one of [weakTargetTokens] to be present. MT does not
     * only soften profanity into a recognizable marker, it also drops it outright
     * ("Fuck you!" -> "Git buradan!"), and requiring a marker made every such line
     * invisible — which kept the whole enToTr table from ever being consulted.
     */
    fun looksSanitized(sourceLine: String, targetLine: String): Boolean {
        if (!sourceHasProfanity(sourceLine)) return false
        return !hasHarshEquivalent(targetLine.lowercase())
    }

    /**
     * Restores the source's register on a line MT softened.
     *
     * A soft marker is only overwritten by the term it is actually the softening OF.
     * [sanitizedMarkers] already encodes that binding: every one of its values is an
     * [enToTr] value, so "kötü adam" is the soft form of whatever term renders as
     * "şerefsiz" (motherfucker), and nothing else may claim that slot. Without the
     * binding the repair grabbed the first weak word in the line, which is how a
     * dropped "fucking" landed on an unrelated "Tanrım".
     *
     * When no marker in the line is bound to a term the source actually used, the line
     * is returned untouched — an unrepairable line is left alone rather than guessed at.
     */
    fun hardenTranslation(sourceLine: String, targetLine: String): String {
        var result = targetLine
        val lowerSource = sourceLine.lowercase()
        // longest matches first, so "son of a bitch" wins over "bitch"
        val hits = enToTr.entries
            .filter { lowerSource.contains(it.key) }
            .sortedByDescending { it.key.length }
        for ((_, tr) in hits) {
            // skip if the target already carries this harsh equivalent
            if (result.lowercase().contains(tr.lowercase())) continue
            // the only eligible slots are markers registered as the soft form of THIS term
            val slot = sanitizedMarkers.entries
                .firstOrNull { (weak, harsh) -> harsh == tr && result.lowercase().contains(weak) }
                ?: continue
            result = result.replace(slot.key, tr, ignoreCase = true)
        }
        return result
    }
}
