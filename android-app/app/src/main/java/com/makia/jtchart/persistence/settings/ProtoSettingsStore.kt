package com.makia.jtchart.persistence.settings

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.dataStoreFile
import com.google.protobuf.InvalidProtocolBufferException
import com.makia.jtchart.domain.market.CandleInterval
import com.makia.jtchart.domain.market.MarketSource
import com.makia.jtchart.domain.settings.AlgorithmSettings
import com.makia.jtchart.domain.settings.AppSettings
import com.makia.jtchart.domain.settings.SettingsStore
import com.makia.jtchart.persistence.settings.proto.AppSettingsProto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.InputStream
import java.io.OutputStream

object AppSettingsSerializer : Serializer<AppSettingsProto> {
    override val defaultValue: AppSettingsProto = AppSettings().toProto()

    override suspend fun readFrom(input: InputStream): AppSettingsProto = try {
        AppSettingsProto.parseFrom(input)
    } catch (failure: InvalidProtocolBufferException) {
        throw CorruptionException("Cannot read JT Chart settings", failure)
    }

    override suspend fun writeTo(t: AppSettingsProto, output: OutputStream) = t.writeTo(output)
}

class ProtoSettingsStore(
    private val dataStore: DataStore<AppSettingsProto>,
    private val onReadFailure: (Throwable) -> Unit = {},
) : SettingsStore {
    override suspend fun load(): AppSettings = try {
        dataStore.data.first().toDomain()
    } catch (failure: Throwable) {
        if (failure is CancellationException) throw failure
        onReadFailure(failure)
        AppSettings()
    }

    override suspend fun save(settings: AppSettings) {
        dataStore.updateData { settings.toProto() }
    }
}

object SettingsPersistenceFactory {
    fun createDataStore(context: Context): DataStore<AppSettingsProto> = DataStoreFactory.create(
        serializer = AppSettingsSerializer,
        produceFile = { context.applicationContext.dataStoreFile("jt-chart-settings.pb") },
    )

    fun createStore(context: Context, onReadFailure: (Throwable) -> Unit = {}): SettingsStore =
        ProtoSettingsStore(createDataStore(context), onReadFailure)
}

internal fun AppSettings.toProto(): AppSettingsProto = AppSettingsProto.newBuilder()
    .setSchemaVersion(schemaVersion)
    .addAllSymbols(symbols)
    .setCurrentSymbol(currentSymbol)
    .setSource(source.wireName)
    .setInterval(interval.wireName)
    .setLimit(limit)
    .setMomStart(algorithm.momStart)
    .setMomEnd(algorithm.momEnd)
    .setZLength(algorithm.zLength)
    .setExtremeThreshold(algorithm.extremeThreshold)
    .setSmoothLength(algorithm.smoothLength)
    .setBearWmaLength(algorithm.bearWmaLength)
    .setAutoRefreshSeconds(autoRefreshSeconds)
    .setSignalNotificationsEnabled(signalNotificationsEnabled)
    .build()

internal fun AppSettingsProto.toDomain(): AppSettings {
    require(schemaVersion == 1) { "Unsupported settings schema: $schemaVersion" }
    return AppSettings(
        schemaVersion = schemaVersion,
        symbols = symbolsList,
        currentSymbol = currentSymbol,
        source = MarketSource.fromWireName(source),
        interval = CandleInterval.fromWireName(interval),
        limit = limit,
        algorithm = AlgorithmSettings(
            momStart = momStart,
            momEnd = momEnd,
            zLength = zLength,
            extremeThreshold = extremeThreshold,
            smoothLength = smoothLength,
            bearWmaLength = bearWmaLength,
        ),
        autoRefreshSeconds = autoRefreshSeconds,
        signalNotificationsEnabled = if (hasSignalNotificationsEnabled()) {
            signalNotificationsEnabled
        } else {
            AppSettings().signalNotificationsEnabled
        },
    )
}
