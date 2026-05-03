// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client

sealed interface AiStreamEvent {
    data class Delta(val text: String) : AiStreamEvent
    data object Done : AiStreamEvent
    data class Error(val type: ErrorType, val message: String) : AiStreamEvent
}
