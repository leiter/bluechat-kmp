package com.bluechat.data.ble

import com.juul.kable.Peripheral

/** CoreBluetooth negotiates the MTU itself, so there is nothing to request here. */
internal actual suspend fun Peripheral.requestLargeMtu() = Unit
