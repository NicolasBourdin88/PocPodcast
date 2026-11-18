package fr.podcast.server.sync

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** The namespace of `podcast:guid` (Podcast Namespace). */
private val PODCAST_NAMESPACE: UUID = UUID.fromString("ead4c236-bf58-58c6-a2c6-a6b28d128cb6")

/**
 * The `podcast:guid` of a feed, as the Podcast Namespace defines it: a UUIDv5 of its URL without
 * the scheme and the trailing slashes. The Open Podcast API names subscriptions with it (ADR 0011).
 */
fun podcastGuid(feedUrl: String): String {
    val name = feedUrl.trim().replace(Regex("^[A-Za-z][A-Za-z0-9+.-]*://"), "").trimEnd('/')
    val sha1 = MessageDigest.getInstance("SHA-1")
    sha1.update(ByteBuffer.allocate(16).putLong(PODCAST_NAMESPACE.mostSignificantBits).putLong(PODCAST_NAMESPACE.leastSignificantBits).array())
    val hash = sha1.digest(name.toByteArray(Charsets.UTF_8))
    hash[6] = (hash[6].toInt() and 0x0f or 0x50).toByte() // version 5
    hash[8] = (hash[8].toInt() and 0x3f or 0x80).toByte() // IETF variant
    val buffer = ByteBuffer.wrap(hash, 0, 16)
    return UUID(buffer.long, buffer.long).toString()
}
