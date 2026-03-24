package com.ernos.mobile.glasses

/**
 * GlassesState
 *
 * Lifecycle state for the Meta Ray-Ban glasses connection.
 */
enum class GlassesState {
    /** BLE scanning has not started yet. */
    DISCONNECTED,

    /** Actively scanning for Meta Ray-Ban glasses via BLE. */
    SCANNING,

    /** BLE GATT connection established; negotiating Wi-Fi Direct for frame streaming. */
    PAIRING,

    /** Fully paired and frame/audio streams are active. */
    STREAMING,

    /** BLE or Wi-Fi Direct link dropped; retrying. */
    RECONNECTING,

    /** Unrecoverable error (e.g. BLE not supported on this device). */
    ERROR,
}
