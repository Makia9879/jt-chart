package com.makia.jtchart.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.market.Query
import com.makia.jtchart.domain.signal.ChartSignal
import com.makia.jtchart.notifications.AndroidSignalNotifier

class DebugSignalNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val query = Query.normalized(
            MarketSource.fromWireName(intent.getStringExtra(EXTRA_SOURCE) ?: MarketSource.BINANCE_SPOT.wireName),
            intent.getStringExtra(EXTRA_SYMBOL) ?: "BTCUSDT",
            CandleInterval.fromWireName(intent.getStringExtra(EXTRA_INTERVAL) ?: CandleInterval.ONE_HOUR.wireName),
        )
        val requested = intent.getStringExtra(EXTRA_SIGNAL) ?: "all"
        val signalNames = if (requested == "all") {
            SUPPORTED_SIGNALS
        } else {
            listOf(requested).filter { it in SUPPORTED_SIGNALS }
        }
        val notifier = AndroidSignalNotifier(context)
        val now = System.currentTimeMillis() / 1000
        signalNames.forEachIndexed { index, name ->
            notifier.notify(
                query,
                ChartSignal(
                    id = "debug:${name}:${now + index}",
                    time = now + index,
                    text = name,
                    type = "debug",
                    strength = if (name.startsWith("确认")) "confirmed" else "tentative",
                    score = if (name.contains("抄底")) -1.25 else 1.25,
                    close = 100.0 + index,
                ),
            )
        }
    }

    companion object {
        const val ACTION = "com.makia.jtchart.DEBUG_SIGNAL_NOTIFICATION"
        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_SYMBOL = "symbol"
        private const val EXTRA_INTERVAL = "interval"
        private const val EXTRA_SIGNAL = "signal"
        private val SUPPORTED_SIGNALS = listOf("试探抄底", "确认抄底", "试探逃顶", "确认逃顶")
    }
}
