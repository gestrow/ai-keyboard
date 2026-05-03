// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client

import com.aikeyboard.app.ai.persona.FewShot
import kotlinx.coroutines.flow.Flow

// Plain interface, not sealed: Kotlin sealed types require subtypes in the same package,
// and the prompt places RemoteApiBackend in `client.remote` (with LocalLanBackend +
// TermuxBridgeBackend coming in their own subpackages later). BackendStrategy carries the
// closed-set semantics that would otherwise live on a sealed type here.
interface AiClient {
    fun isAvailable(): Boolean

    fun rewrite(
        input: String,
        systemPrompt: String,
        fewShots: List<FewShot> = emptyList(),
    ): Flow<AiStreamEvent>
}
