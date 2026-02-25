package com.bluechat.util

enum class SignalStrength(val bars: Int) {
    Excellent(4),
    Good(3),
    Fair(2),
    Poor(1),
    None(0);

    companion object {
        /**
         * Maps RSSI signal strength to signal bars
         *
         * Signal Strength Mapping:
         * - Excellent (4 bars): RSSI >= -50 dBm
         * - Good (3 bars): RSSI >= -60 dBm
         * - Fair (2 bars): RSSI >= -70 dBm
         * - Poor (1 bar): RSSI < -70 dBm
         */
        fun fromRssi(rssi: Int?): SignalStrength {
            if (rssi == null) return None
            return when {
                rssi >= -50 -> Excellent
                rssi >= -60 -> Good
                rssi >= -70 -> Fair
                else -> Poor
            }
        }
    }
}
