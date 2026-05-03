// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.remote

/**
 * Plain-data view of an HTTPS request to a remote provider. Phase 3a builds these
 * from {@code AiClient.rewrite(...)} input but does not dispatch them; Phase 3b
 * passes them to the Ktor HttpClient and handles the streamed response.
 *
 * URL is the fully composed endpoint including any query params (Gemini's
 * {@code ?alt=sse&key=...}). Headers are case-insensitive on the wire; we keep
 * them as a map for testability.
 */
data class RemoteApiRequest(
    val url: String,
    val headers: Map<String, String>,
    val jsonBody: String,
)
