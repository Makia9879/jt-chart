package com.makia.jtchart

import android.app.Application
import com.makia.jtchart.data.market.MarketDataFactory
import com.makia.jtchart.notifications.AndroidSignalNotifier
import com.makia.jtchart.persistence.market.RoomPersistenceFactory
import com.makia.jtchart.persistence.settings.SettingsPersistenceFactory
import com.makia.jtchart.ui.ChartViewModel

class JtChartApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

class AppContainer(application: Application) {
    private val database = RoomPersistenceFactory.createDatabase(application)
    private val snapshotCache = RoomPersistenceFactory.createCache(database)
    private val settingsStore = SettingsPersistenceFactory.createStore(application)
    private val repository = MarketDataFactory.createRepository()
    private val signalNotifier = AndroidSignalNotifier(application)

    val viewModelFactory = ChartViewModel.Factory(repository, settingsStore, snapshotCache, signalNotifier)
}
