package com.bluechat.domain.model

data class BleDevice(
    val identifier: String,
    val name: String?,
    val rssi: Int? = null,
    /** When this device's advertisement was last received, epoch millis. */
    val lastSeenAt: Long = 0L,
) {
    companion object {
        /**
         * How long a device counts as "nearby" after its last advertisement (B5), and how long
         * it stays in the scan list (defect #12). Android and iOS (kable's default
         * `allowDuplicateKeys`) both re-report an advertiser every few seconds while it is
         * in range, so 30 s means "several advertisements in a row were missed".
         */
        const val NEARBY_WINDOW_MILLIS: Long = 30_000L
    }
}
