// SPDX-License-Identifier: GPL-3.0-only
package com.aikeyboard.app.ai.client

enum class ErrorType {
    NETWORK_FAILURE,
    AUTH_FAILURE,
    NO_API_KEY,
    RATE_LIMITED,
    TIMEOUT,
    NOT_IMPLEMENTED,
    UNKNOWN,
}
