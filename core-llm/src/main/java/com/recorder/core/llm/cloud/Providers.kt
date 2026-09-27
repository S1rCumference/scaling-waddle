package com.recorder.core.llm.cloud

/**
 * Where summaries are sent, and what to call it on screen.
 *
 * All three speak the OpenAI chat-completions shape — same path, same `Authorization: Bearer`
 * header, same `{model, messages}` body — which is the whole reason there is one client and not
 * three. Each URL below was taken from that provider's own primary source rather than from
 * memory: Groq's from the `groq-python` client, Google's from the Gemini cookbook's OpenAI
 * compatibility quickstart, Hugging Face's from the Inference Providers documentation.
 *
 * [baseUrl] is a default, not a constant. It is editable in Settings, because a provider moving
 * a URL must not need an app update — and because a provider this list does not name still works
 * if it speaks the same shape.
 */
enum class CloudProvider(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    /** Where the user gets a key, shown next to the field. */
    val keyUrl: String,
    /** What the free allowance actually is, in one line, as far as it could be established. */
    val freeTier: String,
) {
    GOOGLE(
        label = "Google AI Studio",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        defaultModel = "gemini-2.0-flash",
        keyUrl = "https://aistudio.google.com/apikey",
        freeTier = "A genuinely free tier with per-minute and per-day request limits rather " +
            "than a dollar budget. The limits are not published anywhere this build could " +
            "read, so the app discovers them: it backs off when told to and says so.",
    ),

    GROQ(
        label = "Groq",
        baseUrl = "https://api.groq.com/openai/v1",
        defaultModel = "llama-3.3-70b-versatile",
        keyUrl = "https://console.groq.com/keys",
        freeTier = "A free tier with per-minute and per-day request limits. Same as above: " +
            "discovered at run time, not assumed.",
    ),

    HUGGING_FACE(
        label = "Hugging Face",
        baseUrl = "https://router.huggingface.co/v1",
        defaultModel = "openai/gpt-oss-120b:cheapest",
        keyUrl = "https://huggingface.co/settings/tokens",
        freeTier = "Free accounts get about \$0.10 of inference credit a month, which is a " +
            "few hundred thousand tokens — enough for day and week summaries, not enough to " +
            "summarise every hour of every day. PRO raises it to \$2.00.",
    ),
    ;

    /** The full endpoint, tolerating a base URL pasted with or without a trailing slash. */
    fun chatCompletionsUrl(base: String = baseUrl): String =
        base.trimEnd('/') + "/chat/completions"

    companion object {
        val DEFAULT = GOOGLE

        fun byName(name: String?): CloudProvider =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
