package com.makia.jtchart.domain.signal

data class ChartSignal(
    val id: String,
    val time: Long,
    val text: String,
    val type: String,
    val strength: String,
    val score: Double,
    val close: Double,
)
