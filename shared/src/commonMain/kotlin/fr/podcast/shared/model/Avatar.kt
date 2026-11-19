package fr.podcast.shared.model

/**
 * The profile pictures to choose from, as on streaming services (ADR 0013): an emoji on a color.
 * Only the id travels (a user's `avatar`); every client draws the picture from this list, so that
 * a new picture only needs a new entry here.
 */
enum class Avatar(val id: String, val emoji: String, val color: Int) {
    Fox("fox", "🦊", 0xE8590C),
    Panda("panda", "🐼", 0x2F9E44),
    Owl("owl", "🦉", 0x7048E8),
    Octopus("octopus", "🐙", 0xD6336C),
    Frog("frog", "🐸", 0x0C8599),
    Tiger("tiger", "🐯", 0xF08C00),
    Penguin("penguin", "🐧", 0x1971C2),
    Whale("whale", "🐳", 0x1098AD),
    Unicorn("unicorn", "🦄", 0xAE3EC9),
    Koala("koala", "🐨", 0x5C7CFA),
    Headphones("headphones", "🎧", 0x495057),
    Rocket("rocket", "🚀", 0xC92A2A),
    ;

    companion object {
        val ids: List<String> get() = entries.map { it.id }

        fun of(id: String?): Avatar? = entries.firstOrNull { it.id == id }

        /** Before any choice: one taken from the username, always the same. */
        fun default(username: String): Avatar = entries[username.hashCode().mod(entries.size)]

        /** The user's choice, or else their default. */
        fun of(id: String?, username: String): Avatar = of(id) ?: default(username)
    }
}
