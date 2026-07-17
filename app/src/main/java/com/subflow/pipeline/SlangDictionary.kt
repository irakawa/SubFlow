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

    // archetype voice patterns to tune tone (villain, comedic, tsundere, stoic)
    val archetypePatterns: Map<String, List<Pair<String, String>>> = mapOf(
        "villain" to listOf(
            "you fool" to "seni ahmak",
            "pathetic" to "acınasısın",
            "kneel" to "diz çök",
            "worthless" to "değersiz böcek"
        ),
        "comedic" to listOf(
            "oops" to "eyvah",
            "my bad" to "benim hatam ya",
            "yikes" to "öf be",
            "awkward" to "garip kaçtı şimdi"
        ),
        "tsundere" to listOf(
            "it's not like i" to "sanki senin için yaptım da",
            "baka" to "aptal",
            "whatever" to "her neyse işte"
        ),
        "stoic" to listOf(
            "i see" to "anlıyorum",
            "very well" to "pekâlâ",
            "so be it" to "öyle olsun"
        )
    )

    // soft markers. harsh source but one of these in the target means harden the line.
    val sanitizedMarkers: Map<String, String> = mapOf(
        "lanet olsun" to "kahretsin",       // depends on the "damn" context
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

    private val weakTargetTokens = listOf(
        "tanrım", "aman tanrım", "hay aksi", "kahretsin", "lanet", "kötü adam", "aptal"
    )

    private val strongTargetTokens = listOf(
        "siktir", "bok", "orospu", "piç", "kaltak", "şerefsiz", "göt", "yavşak", "sıçayım", "lan"
    )

    // sanitized = harsh source, no harsh equivalent in target, and a known soft marker present
    fun looksSanitized(sourceLine: String, targetLine: String): Boolean {
        if (!sourceHasProfanity(sourceLine)) return false
        val lowerTarget = targetLine.lowercase()
        return strongTargetTokens.none { lowerTarget.contains(it) } &&
            weakTargetTokens.any { lowerTarget.contains(it) }
    }

    // apply the dictionary's hard equivalent to the target
    fun hardenTranslation(sourceLine: String, targetLine: String): String {
        var result = targetLine
        val lowerSource = sourceLine.lowercase()
        // longest matches first
        val hits = enToTr.entries
            .filter { lowerSource.contains(it.key) }
            .sortedByDescending { it.key.length }
        for ((en, tr) in hits) {
            // skip if the target already has a harsh equivalent
            if (result.lowercase().contains(tr.lowercase())) continue
            // swap a soft marker for the harsh one
            var replaced = false
            for ((weak, _) in sanitizedMarkers) {
                if (result.lowercase().contains(weak)) {
                    result = result.replace(weak, tr, ignoreCase = true)
                    replaced = true
                    break
                }
            }
            if (!replaced && SlangDictionary.enToTr.containsKey(en)) {
                // replace weak generic expressions
                for (weakToken in weakTargetTokens) {
                    if (result.lowercase().contains(weakToken)) {
                        result = result.replace(weakToken, tr, ignoreCase = true)
                        replaced = true
                        break
                    }
                }
            }
            // couldn't place it cleanly, skip and keep hardening the rest
            if (!replaced) continue
        }
        return result
    }
}
