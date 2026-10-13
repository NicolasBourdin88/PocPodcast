package poc.hypermedia

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// The same episode, with the same three actions, in each format.
// "mark-played" is only offered while the episode is not played: the server drives the available transitions.

fun halFormsEpisode(episode: Episode, playlists: Collection<Playlist>): JsonObject = buildJsonObject {
    putJsonObject("_links") {
        putJsonObject("self") { put("href", "/episodes/${episode.id}") }
    }
    put("id", episode.id)
    put("title", episode.title)
    put("positionSeconds", episode.positionSeconds)
    put("played", episode.played)
    putJsonObject("_templates") {
        putJsonObject("update-position") {
            put("title", "Mettre à jour la position")
            put("method", "PUT")
            put("target", "/episodes/${episode.id}/position")
            put("contentType", "application/json")
            putJsonArray("properties") {
                addJsonObject {
                    put("name", "position"); put("type", "number"); put("required", true); put("min", 0)
                    put("value", episode.positionSeconds)
                }
            }
        }
        if (!episode.played) {
            putJsonObject("mark-played") {
                put("title", "Marquer comme écouté")
                put("method", "POST")
                put("target", "/episodes/${episode.id}/played")
            }
        }
        putJsonObject("add-to-playlist") {
            put("title", "Ajouter à une playlist")
            put("method", "POST")
            put("target", "/playlist-items")
            put("contentType", "application/json")
            putJsonArray("properties") {
                addJsonObject { put("name", "episodeId"); put("readOnly", true); put("value", episode.id) }
                addJsonObject {
                    put("name", "playlistId"); put("required", true); put("prompt", "Playlist")
                    putJsonObject("options") {
                        putJsonArray("inline") {
                            playlists.forEach { addJsonObject { put("prompt", it.title); put("value", it.id) } }
                        }
                        put("maxItems", 1)
                    }
                }
            }
        }
    }
}

fun sirenEpisode(episode: Episode, playlists: Collection<Playlist>): JsonObject = buildJsonObject {
    putJsonArray("class") { add("episode") }
    putJsonObject("properties") {
        put("id", episode.id)
        put("title", episode.title)
        put("positionSeconds", episode.positionSeconds)
        put("played", episode.played)
    }
    putJsonArray("actions") {
        addJsonObject {
            sirenAction("update-position", "PUT", "/episodes/${episode.id}/position")
            putJsonArray("fields") {
                addJsonObject { put("name", "position"); put("type", "number"); put("value", episode.positionSeconds) }
            }
        }
        if (!episode.played) {
            addJsonObject { sirenAction("mark-played", "POST", "/episodes/${episode.id}/played") }
        }
        addJsonObject {
            sirenAction("add-to-playlist", "POST", "/playlist-items")
            putJsonArray("fields") {
                addJsonObject { put("name", "episodeId"); put("type", "hidden"); put("value", episode.id) }
                // Siren fields have no list of choices (and field names must be unique, so one
                // "radio" field per playlist is not allowed): the client must fetch playlists itself.
                addJsonObject { put("name", "playlistId"); put("type", "text") }
            }
        }
    }
    putJsonArray("links") {
        addJsonObject { putJsonArray("rel") { add("self") }; put("href", "/episodes/${episode.id}") }
    }
}

private fun JsonObjectBuilder.sirenAction(name: String, method: String, href: String) {
    put("name", name)
    put("method", method)
    put("href", href)
    put("type", "application/json")
}

/** JSON:API describes data and links, not actions: clients must know PATCH semantics out of band. */
fun jsonApiEpisode(episode: Episode): JsonObject = buildJsonObject {
    putJsonObject("data") {
        put("type", "episodes")
        put("id", episode.id)
        putJsonObject("attributes") {
            put("title", episode.title)
            put("positionSeconds", episode.positionSeconds)
            put("played", episode.played)
        }
        putJsonObject("links") { put("self", "/episodes/${episode.id}") }
    }
}
