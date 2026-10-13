package fr.podcast.shared.i18n

/** Supported interface languages. Each client picks one from its platform locale. */
enum class Language(val tag: String, val strings: Strings) {
    French("fr", FrenchStrings),
    English("en", EnglishStrings);

    companion object {
        /** Used when none of the user's languages is supported. */
        val Default = English

        /** Matches a BCP 47 tag or POSIX locale ("fr", "fr-CA", "fr_FR.UTF-8") on its primary language. */
        fun fromTag(tag: String?): Language? {
            val primary = tag?.substringBefore('-')?.substringBefore('_')?.substringBefore('.')?.lowercase()
            return entries.firstOrNull { it.tag == primary }
        }

        /** First supported language among [tags], ordered by preference, or [Default]. */
        fun negotiate(tags: List<String>): Language = tags.firstNotNullOfOrNull(::fromTag) ?: Default
    }
}
