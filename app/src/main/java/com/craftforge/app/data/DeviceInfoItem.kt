package com.craftforge.app.data

/**
 * Модель даних для одного рядка інформації про пристрій
 */
data class DeviceInfoItem(
    val label: String,
    val value: String,
    val isError: Boolean = false
)
