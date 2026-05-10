// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client.locallan

enum class LocalLanApiFormat {
    /** Ollama-native: POST <base>/api/chat, line-delimited JSON streaming. */
    OLLAMA,
    /** OpenAI-compatible: POST <base>/v1/chat/completions, SSE streaming. */
    OPENAI_COMPATIBLE,
}
