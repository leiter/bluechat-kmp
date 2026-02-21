package com.bluechat.data.ble

import kotlin.uuid.Uuid

object BleGattProfile {
    val SERVICE_UUID: Uuid = Uuid.parse("1706BBC0-88AB-4B8D-877E-2237916EE929")
    val CHARACTERISTIC_MESSAGE_UUID: Uuid = Uuid.parse("275348FB-C14D-4FD5-B434-7C3F351DEA5F")
    val DESCRIPTOR_MESSAGE_UUID: Uuid = Uuid.parse("45bda094-ff40-4cb8-835d-0da8742bb1eb")
    val CHARACTERISTIC_BLE_TRANSFER_UUID: Uuid = Uuid.parse("482f1096-137b-46cc-8ca8-3457c15cc433")
    val DESCRIPTOR_BLE_TRANSFER_UUID: Uuid = Uuid.parse("421ecb34-bb49-4b70-a5ea-042c1f38ec32")
}
