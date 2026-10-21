package com.vayunmathur.uniflix.domain

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

/** Failures when adding or refreshing a URL addon, surfaced per-addon in the manager UI. */
sealed class ManifestError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** Manifest is valid JSON but cannot supply streams (no stream resource / wrong types). */
    class Unsupported(message: String) : ManifestError(message)

    /** The manifest URL could not be fetched (DNS, timeout, non-2xx). */
    class Network(message: String) : ManifestError(message)

    /** The manifest URL returned something that is not a Stremio manifest. */
    class Parse(message: String, cause: Throwable? = null) : ManifestError(message, cause)

    /** Plain-http addon outside loopback/private networks — addons carry stream URLs, so no cleartext. */
    class InsecureUrl(url: String) : ManifestError("Refusing non-HTTPS addon URL: $url")
}

/** One entry of a Stremio manifest `resources` array (string or object form). */
@Serializable
data class AddonResource(
    val name: String,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
)

/** Accepts Stremio's string-or-object resource forms: `"stream"` or `{"name":"stream",...}`. */
object ResourceListSerializer : KSerializer<List<AddonResource>> {
    private val delegate = ListSerializer(AddonResource.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): List<AddonResource> {
        require(decoder is JsonDecoder) { "Addon resources require a JSON manifest" }
        val array = decoder.decodeJsonElement() as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonPrimitive -> element.jsonPrimitive.content.takeIf { it.isNotBlank() }
                    ?.let { AddonResource(name = it) }
                is JsonObject -> runCatching {
                    decoder.json.decodeFromJsonElement(AddonResource.serializer(), element)
                }.getOrNull()
                else -> null
            }
        }
    }

    override fun serialize(encoder: Encoder, value: List<AddonResource>) {
        delegate.serialize(encoder, value)
    }
}

/**
 * Subset of the Stremio addon manifest Uniflix supports: identity + stream
 * resources for movies/series. Catalog, meta, and subtitles resources are ignored.
 */
@Serializable
data class AddonManifest(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String = "",
    @Serializable(with = ResourceListSerializer::class)
    val resources: List<AddonResource> = emptyList(),
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
) {
    /**
     * Rejects manifests that cannot supply Uniflix streams. Returns this on
     * success so callers can parse-and-validate in one expression.
     */
    fun validate(): AddonManifest {
        if (resources.none { it.name == "stream" }) {
            error("Addon '$name' does not offer streams")
        }
        val offered = (types + resources.flatMap { it.types }).map { it.lowercase() }.toSet()
        if (offered.isNotEmpty() && "movie" !in offered && "series" !in offered) {
            error("Addon '$name' serves $offered, not movies or series")
        }
        return this
    }

    private fun error(message: String): Nothing = throw ManifestError.Unsupported(message)
}
