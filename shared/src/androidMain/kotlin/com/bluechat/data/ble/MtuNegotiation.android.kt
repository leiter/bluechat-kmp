package com.bluechat.data.ble

import com.juul.kable.AndroidPeripheral
import com.juul.kable.Peripheral

internal actual suspend fun Peripheral.requestLargeMtu() {
    val android = this as? AndroidPeripheral ?: return
    // A peer may refuse or negotiate downwards; whatever is agreed is reported back through
    // maximumWriteValueLengthForType, so a failure here is not fatal.
    runCatching { android.requestMtu(DESIRED_MTU) }
}
