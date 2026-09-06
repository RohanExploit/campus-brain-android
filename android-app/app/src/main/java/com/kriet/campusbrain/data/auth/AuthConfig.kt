package com.kriet.campusbrain.data.auth

import org.json.JSONObject
import java.io.File

/**
 * Where the Supabase project URL and publishable (anon) key come from.
 *
 * The same `config.json` the cloud answer fallback reads, found the same way
 * -- external files dir first, internal second. Two consequences worth being
 * explicit about:
 *
 *  - **No key appears in source.** A build with no `config.json` has no auth,
 *    exactly as it has no cloud fallback, and every path below returns null
 *    rather than failing. That is a supported configuration: retrieval works.
 *  - The lookup is duplicated here rather than calling `CloudAnswer`'s copy.
 *    Four lines of duplication buys `data/auth` zero compile-time coupling to
 *    `answer/`, and `CloudAnswer.parseConfig` returns null unless a Groq or
 *    Ollama value is present -- so a Supabase-only config would be discarded
 *    by it.
 *
 * The anon key is public by design: it identifies the project and carries no
 * authority of its own. Every row this app can read or write is decided by RLS
 * against the user's JWT, and tenancy is resolved server-side from
 * `memberships`, never from anything the client sends.
 */
data class AuthConfig(
    /** e.g. https://cdsakhcvdwbbafdcwdux.supabase.co, no trailing slash. */
    val url: String,
    val anonKey: String,
    /**
     * Domain for the synthetic address used when a student has no email they
     * want to give. See [SupabaseAuth.syntheticEmail] for why an address is
     * needed at all when it proves nothing.
     */
    val syntheticEmailDomain: String = DEFAULT_SYNTHETIC_DOMAIN,
) {
    val authBase: String get() = "$url/auth/v1"
    val restBase: String get() = "$url/rest/v1"

    companion object {
        const val CONFIG_FILE_NAME = "config.json"
        const val DEFAULT_SYNTHETIC_DOMAIN = "student.campusbrain.invalid"

        /** Same order as BrainDb and CloudAnswer: external, then internal. */
        fun findConfigFile(externalDir: File?, internalDir: File): File? {
            val external = externalDir?.let { File(it, CONFIG_FILE_NAME) }
            if (external != null && external.exists()) return external
            val internal = File(internalDir, CONFIG_FILE_NAME)
            return if (internal.exists()) internal else null
        }

        fun load(externalDir: File?, internalDir: File): AuthConfig? = runCatching {
            val file = findConfigFile(externalDir, internalDir) ?: return null
            parse(file.readText())
        }.getOrNull()

        /**
         * Parses `{"supabase_url": "...", "supabase_anon_key": "..."}` out of
         * the shared config file, ignoring every other key in it.
         *
         * Both values are required: a URL with no key cannot authenticate and a
         * key with no URL has nowhere to go, and half a configuration is worse
         * than none because it produces a sign-in button that always fails.
         */
        fun parse(text: String): AuthConfig? = runCatching {
            val json = JSONObject(text)
            val url = json.optString("supabase_url").trim().trimEnd('/')
                .takeIf { it.isNotBlank() } ?: return null
            // Nothing but https reaches a hosted identity provider. A plain
            // http URL in a config file is a typo or a downgrade, and there is
            // no development case for it here -- Supabase is hosted.
            if (!url.startsWith("https://")) return null
            val key = json.optString("supabase_anon_key").trim()
                .takeIf { it.isNotBlank() } ?: return null
            val domain = json.optString("synthetic_email_domain").trim()
                .takeIf { it.isNotBlank() } ?: DEFAULT_SYNTHETIC_DOMAIN
            AuthConfig(url, key, domain)
        }.getOrNull()
    }
}
