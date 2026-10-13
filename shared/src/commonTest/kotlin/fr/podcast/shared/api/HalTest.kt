package fr.podcast.shared.api

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class HalTest {

    @Test
    fun uriTemplatesAreExpanded() {
        assertEquals("/episodes", expandUriTemplate("/episodes{?status,offset,limit}", emptyMap()))
        assertEquals("/episodes?status=IN_PROGRESS&limit=5", expandUriTemplate("/episodes{?status,offset,limit}", mapOf("status" to "IN_PROGRESS", "limit" to 5)))
        assertEquals("/episodes/42", expandUriTemplate("/episodes/{id}", mapOf("id" to "42")))
        assertEquals("/podcasts/a%20b", expandUriTemplate("/podcasts/{id}", mapOf("id" to "a b")))
    }

    @Test
    fun linksEmbeddedResourcesAndTemplatesAreRead() {
        val resource = HalResource(
            Json.parseToJsonElement(
                """
                {
                  "id": "1",
                  "_links": { "self": { "href": "/episodes/1" } },
                  "_embedded": { "podcast": { "id": "7" } },
                  "_templates": {
                    "update-progress": {
                      "method": "PUT", "target": "/episodes/1/progress",
                      "properties": [ { "name": "positionSeconds", "required": true, "value": 30 } ]
                    }
                  }
                }
                """.trimIndent(),
            ).jsonObject,
        )

        assertEquals("/episodes/1", resource.link("self"))
        assertEquals("7", resource.embedded("podcast").single().string("id"))
        val template = resource.template("update-progress")!!
        assertEquals(HttpMethod.Put, template.method)
        assertEquals("/episodes/1/progress", template.target)
        assertEquals(JsonPrimitive(30), template.properties.single().value)
        assertEquals(setOf("update-progress"), resource.templateNames)
    }
}
