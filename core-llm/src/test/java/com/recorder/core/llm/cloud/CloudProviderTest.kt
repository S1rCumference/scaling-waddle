package com.recorder.core.llm.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The provider presets, and the one bit of string handling between a pasted URL and a request.
 *
 * The base URL is editable, which means a person types it, which means it will arrive with a
 * trailing slash about half the time. `…/v1//chat/completions` is a 404 on every provider here and
 * would read to the user as "the key does not work".
 */
class CloudProviderTest {

    @Test
    fun `a base url without a trailing slash gets exactly one`() {
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            CloudProvider.GROQ.chatCompletionsUrl("https://api.groq.com/openai/v1"),
        )
    }

    @Test
    fun `a base url with a trailing slash does not end up with two`() {
        assertEquals(
            "https://api.groq.com/openai/v1/chat/completions",
            CloudProvider.GROQ.chatCompletionsUrl("https://api.groq.com/openai/v1/"),
        )
    }

    @Test
    fun `several trailing slashes are all absorbed`() {
        assertEquals(
            "https://example.test/v1/chat/completions",
            CloudProvider.GROQ.chatCompletionsUrl("https://example.test/v1///"),
        )
    }

    @Test
    fun `each preset's own base url produces a valid endpoint`() {
        CloudProvider.entries.forEach { provider ->
            val url = provider.chatCompletionsUrl()
            assertTrue("${provider.name}: $url", url.startsWith("https://"))
            assertTrue("${provider.name}: $url", url.endsWith("/chat/completions"))
            assertTrue("${provider.name}: $url", "//chat" !in url)
        }
    }

    @Test
    fun `every preset is complete enough to try a request with`() {
        CloudProvider.entries.forEach { provider ->
            assertTrue("${provider.name} needs a model", provider.defaultModel.isNotBlank())
            assertTrue("${provider.name} needs a key url", provider.keyUrl.startsWith("https://"))
            // The free tier is described rather than implied: this text is what the user reads
            // before choosing, and an empty string there is a preset nobody can evaluate.
            assertTrue("${provider.name} needs a free-tier note", provider.freeTier.length > 40)
        }
    }

    @Test
    fun `an unknown or blank provider name falls back to the default rather than throwing`() {
        // This reads a stored preference. A rename, a downgrade, or a corrupt value must not
        // crash the settings screen.
        assertEquals(CloudProvider.DEFAULT, CloudProvider.byName(null))
        assertEquals(CloudProvider.DEFAULT, CloudProvider.byName(""))
        assertEquals(CloudProvider.DEFAULT, CloudProvider.byName("SOME_PROVIDER_THAT_WENT_AWAY"))
    }

    @Test
    fun `a known name round-trips`() {
        CloudProvider.entries.forEach {
            assertEquals(it, CloudProvider.byName(it.name))
        }
    }
}
