package com.bluechat.data.ble

import com.juul.kable.Peripheral

/**
 * Asks for a larger ATT MTU once connected.
 *
 * Android keeps the 23-byte default unless the central explicitly asks for more, and 23 leaves
 * only 20 usable bytes per write. iOS negotiates on its own, so its actual is a no-op; both
 * platforms then report the agreed size through `maximumWriteValueLengthForType`.
 */
internal expect suspend fun Peripheral.requestLargeMtu()

/** The largest MTU defined by the spec; peers are free to negotiate downwards. */
internal const val DESIRED_MTU: Int = 517
