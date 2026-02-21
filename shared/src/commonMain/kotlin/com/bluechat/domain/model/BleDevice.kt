package com.bluechat.domain.model

data class BleDevice(
    val identifier: String,
    val name: String?,
    val rssi: Int? = null,
)
