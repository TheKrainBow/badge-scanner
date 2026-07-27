package fr.fortytwo.badgescanner.util

/**
 * Piscine CA users are named like "[PISCINE] 249 lgauvrea" and carry no
 * ft_login/ft_id — the trailing token is the intra login. Returns null when
 * the name isn't a piscine entry or has no usable login.
 */
fun piscineLoginFromName(fullName: String): String? {
    if (!fullName.contains("[PISCINE]", ignoreCase = true)) return null
    val last = fullName.trim().split(Regex("\\s+")).lastOrNull()?.trim() ?: return null
    // A 42 login is lowercase letters/digits plus '-' or '_'
    // (e.g. "lgauvrea", "mdi-boni"), never the bracket tag or numeric id
    fun isLoginChar(c: Char) = c.isLetterOrDigit() || c == '-' || c == '_'
    return last.takeIf { it.isNotEmpty() && it.all(::isLoginChar) && it.any { c -> c.isLetter() } }
        ?.lowercase()
}
