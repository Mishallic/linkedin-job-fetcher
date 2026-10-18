package com.jobwatch.app

/**
 * Countries and their best-known cities, so a search's place can be matched to a job's location:
 * a job in Dubai answers a search for the United Arab Emirates, one in Riyadh a search for Saudi
 * Arabia. A place it doesn't know is taken as a country name as typed.
 */
object Places {
    private val COUNTRIES = mapOf(
        "Jordan" to listOf("jordan", "amman", "irbid", "zarqa", "aqaba"),
        "United Arab Emirates" to listOf("united arab emirates", "uae", "emirates", "dubai", "abu dhabi", "sharjah",
            "ajman", "al ain", "ras al khaimah", "fujairah", "umm al quwain"),
        "Saudi Arabia" to listOf("saudi arabia", "saudi", "ksa", "riyadh", "jeddah", "dammam", "khobar", "al khobar",
            "dhahran", "jubail", "mecca", "makkah", "medina", "madinah", "tabuk", "neom"),
        "Qatar" to listOf("qatar", "doha"),
        "Kuwait" to listOf("kuwait"),
        "Bahrain" to listOf("bahrain", "manama"),
        "Oman" to listOf("oman", "muscat"),
        "Egypt" to listOf("egypt", "cairo", "alexandria", "giza"),
        "Lebanon" to listOf("lebanon", "beirut"),
    )
    private val NOT_PLACES = setOf("remote", "anywhere", "worldwide", "global")

    private fun parts(place: String) = place.lowercase().split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** The country a search's place is in: "Amman, Jordan" and "Amman" both give Jordan. Null for no place. */
    fun countryOf(place: String): String? {
        val parts = parts(place)
        if (parts.isEmpty() || parts.last() in NOT_PLACES) return null
        COUNTRIES.entries.firstOrNull { (_, names) -> parts.any { it in names } }?.let { return it.key }
        return parts.last().split(" ").joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    /**
     * Whether a job's [location] is in a search's [place]: it names a part of the place, its country,
     * or one of that country's cities. A search without a place takes jobs anywhere.
     */
    fun inPlace(location: String, place: String): Boolean {
        val names = parts(place)
        if (names.isEmpty()) return true
        val all = names + COUNTRIES[countryOf(place)].orEmpty()
        val text = location.lowercase()
        return all.any { Regex("\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
    }
}
