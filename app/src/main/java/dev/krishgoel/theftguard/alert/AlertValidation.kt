package dev.krishgoel.theftguard.alert

/** Input checks for the alert settings. Pure Kotlin, so it's unit-tested on the PC. */
object AlertValidation {

    /** Keep digits and a leading '+', drop spaces, dashes, brackets. */
    fun normalizePhone(raw: String): String {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it in '0'..'9' }
        return if (trimmed.startsWith("+")) "+$digits" else digits
    }

    /** 7–15 digits (the international maximum is 15), optional leading '+'. */
    fun isValidPhone(raw: String): Boolean {
        val n = normalizePhone(raw)
        val digits = n.removePrefix("+")
        return digits.length in 7..15
    }

    /**
     * An IMEI is 15 digits, and the last one is a check digit computed with
     * the Luhn algorithm (the same one bank cards use). So we can catch a
     * mistyped IMEI immediately.
     */
    fun isValidImei(raw: String): Boolean {
        val s = raw.filter { !it.isWhitespace() }
        return s.length == 15 && s.all { it in '0'..'9' } && luhnOk(s)
    }

    /**
     * Luhn: from the right, double every second digit (subtract 9 if that
     * gives more than 9), add everything up; valid if the total ends in 0.
     */
    fun luhnOk(digits: String): Boolean {
        var sum = 0
        digits.reversed().forEachIndexed { i, ch ->
            var v = ch - '0'
            if (i % 2 == 1) {
                v *= 2
                if (v > 9) v -= 9
            }
            sum += v
        }
        return sum % 10 == 0
    }
}
