// SPDX-License-Identifier: GPL-3.0-only

package com.aikeyboard.app.keyboard.clipboard

interface OnKeyEventListener {

    fun onKeyDown(clipId: Long)

    fun onKeyUp(clipId: Long)

}