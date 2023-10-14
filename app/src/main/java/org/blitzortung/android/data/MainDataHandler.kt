/*

   Copyright 2015 Andreas Würl

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

*/

package org.blitzortung.android.data

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.os.Handler
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.blitzortung.android.app.Main
import org.blitzortung.android.app.Main.Companion.LOG_TAG
import org.blitzortung.android.app.R
import org.blitzortung.android.app.view.OnSharedPreferenceChangeListener
import org.blitzortung.android.app.view.PreferenceKey
import org.blitzortung.android.app.view.get
import org.blitzortung.android.data.cache.DataCache
import org.blitzortung.android.data.provider.DataProviderFactory
import org.blitzortung.android.data.provider.DataProviderType
import org.blitzortung.android.data.provider.LocalData
import org.blitzortung.android.data.provider.data.DataProvider
import org.blitzortung.android.data.provider.result.DataEvent
import org.blitzortung.android.data.provider.result.RequestStartedEvent
import org.blitzortung.android.data.provider.result.ResultEvent
import org.blitzortung.android.data.provider.result.StatusEvent
import org.blitzortung.android.location.LocationEvent
import org.blitzortung.android.protocol.ConsumerContainer
import org.blitzortung.android.util.Period
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MainDataHandler @Inject constructor(
    private val context: Context,
    private val dataProviderFactory: DataProviderFactory,
    private val preferences: SharedPreferences,
    private val handler: Handler,
    private val cache: DataCache,
    private val localData: LocalData,
    private val updatePeriod: Period
) : OnSharedPreferenceChangeListener, Runnable, MapListener {

    private var location: Location? = null

    @Volatile
    private var updatesEnabled = false

    private var mode = Mode.DATA

    private var period: Int = 0

    private var dataProvider: DataProvider? = null

    var parameters = Parameters()
        private set

    var history = History()
        private set

    private var autoRaster = false

    private val dataConsumerContainer = object : ConsumerContainer<DataEvent>() {
        override fun addedFirstConsumer() {
            Log.d(Main.LOG_TAG, "MainDataHandler: added first data consumer")
        }

        override fun removedLastConsumer() {
            Log.d(Main.LOG_TAG, "MainDataHandler: removed last data consumer")
        }
    }

    val locationEventConsumer: (LocationEvent) -> Unit = { locationEvent ->
        Log.v(Main.LOG_TAG, "AlertView received location ${locationEvent.location}")
        location = locationEvent.location
    }

    private var dataMode = DataMode()

    init {
        preferences.registerOnSharedPreferenceChangeListener(this)

        updatesEnabled = false
        onSharedPreferenceChanged(
            this.preferences,
            PreferenceKey.DATA_SOURCE,
            PreferenceKey.USERNAME,
            PreferenceKey.PASSWORD,
            PreferenceKey.RASTER_SIZE,
            PreferenceKey.COUNT_THRESHOLD,
            PreferenceKey.REGION,
            PreferenceKey.INTERVAL_DURATION,
            PreferenceKey.HISTORIC_TIMESTEP,
            PreferenceKey.QUERY_PERIOD
        )
        updatesEnabled = true
    }

    fun requestUpdates(dataConsumer: (DataEvent) -> Unit) {
        dataConsumerContainer.addConsumer(dataConsumer)
    }

    fun removeUpdates(dataConsumer: (DataEvent) -> Unit) {
        dataConsumerContainer.removeConsumer(dataConsumer)
    }

    val hasConsumers: Boolean
        get() = dataConsumerContainer.isEmpty

    fun updateData(updateTargets: Set<DataChannel> = DEFAULT_DATA_CHANNELS) {
        if (updatesEnabled) {
            sendEvent(REQUEST_STARTED_EVENT)

            var updateParticipants = false
            if (updateTargets.contains(DataChannel.PARTICIPANTS)) {
                if (dataProvider!!.type == DataProviderType.HTTP || !dataMode.raster) {
                    updateParticipants = true
                }
            }
            updateUsingCache()
        }
    }

    private fun updateUsingCache() {
        val parameters = activeParameters
        val cachedResult = cache.get(parameters)
        if (cachedResult != null) {
            Log.d(LOG_TAG, "MainDataHandler.updateData() cached $parameters")
            sendEvent(cachedResult)
        } else {
            Log.d(LOG_TAG, "MainDataHandler.updateData() fetch $parameters")
            FetchDataTask(dataMode, dataProvider!!, {
                if (!it.containsRealtimeData()) {
                    cache.put(it.parameters, it)
                } else {
                    cache.logStats()
                }
                sendEvent(it)
            }, ::toast).execute(parameters = parameters, history = history)
        }
    }

    private val activeParameters: Parameters
        get() {
            return if (dataMode.raster) {
                localData.updateParameters(parameters, location)
            } else {
                var parameters = parameters
                if (!dataMode.region) {
                    parameters = parameters.copy(region = 0)
                }
                parameters.copy(rasterBaselength = 0, countThreshold = 0)
            }
        }

    private fun sendEvent(dataEvent: DataEvent, cached: Boolean = false) {
        if (dataEvent is ResultEvent && dataEvent.flags.storeResult) {
            dataConsumerContainer.storeAndBroadcast(dataEvent)
        } else {
            dataConsumerContainer.broadcast(dataEvent)
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: PreferenceKey) {
        when (key) {
            PreferenceKey.DATA_SOURCE, PreferenceKey.SERVICE_URL -> {
                val providerTypeString =
                    sharedPreferences.get(PreferenceKey.DATA_SOURCE, DataProviderType.RPC.toString())
                val providerType = DataProviderType.valueOf(providerTypeString.uppercase(Locale.getDefault()))
                val dataProvider = dataProviderFactory.getDataProviderForType(providerType)
                this.dataProvider = dataProvider

                updateProviderSpecifics()

                if (providerTypeString == DataProviderType.HTTP.toString()) {
                    showBlitzortungProviderWarning()
                }

                Log.v(Main.LOG_TAG, "MainDataHandler update data source: $providerType")

                updateData()
            }

            PreferenceKey.RASTER_SIZE -> {
                val rasterBaselengthString = sharedPreferences.get(key, AUTO_RASTER_BASELENGTH)
                if (rasterBaselengthString == AUTO_RASTER_BASELENGTH) {
                    autoRaster = true
                    parameters = parameters.copy(rasterBaselength = DEFAULT_RASTER_BASELENGTH)
                } else {
                    val rasterBaselength = Integer.parseInt(rasterBaselengthString)
                    autoRaster = false
                    parameters = parameters.copy(rasterBaselength = rasterBaselength)
                }
                updateData()
            }

            PreferenceKey.COUNT_THRESHOLD -> {
                val countThreshold = Integer.parseInt(sharedPreferences.get(key, "0"))
                parameters = parameters.copy(countThreshold = countThreshold)
                updateData()
            }

            PreferenceKey.INTERVAL_DURATION -> {
                val intervalDuration = Integer.parseInt(sharedPreferences.get(key, "60"))
                parameters = parameters.withIntervalDuration(intervalDuration)
                updateData()
            }

            PreferenceKey.HISTORIC_TIMESTEP -> {
                history = history.copy(
                    timeIncrement =
                    sharedPreferences.get(key, "30").toInt()
                )
            }

            PreferenceKey.REGION -> {
                val region = Integer.parseInt(sharedPreferences.get(key, "0"))
                parameters = parameters.copy(region = region)
                updateData()
            }

            PreferenceKey.QUERY_PERIOD -> {
                period = Integer.parseInt(sharedPreferences.get(key, "60"))
                Log.v(Main.LOG_TAG, "MainDataHandler query $period")
            }

            else -> {
            }
        }
    }

    private fun showBlitzortungProviderWarning() =
        Toast.makeText(context, R.string.provider_warning, Toast.LENGTH_LONG).show()

    private fun updateProviderSpecifics() {
        val providerType = dataProvider!!.type

        dataMode = when (providerType) {
            DataProviderType.RPC -> DataMode(raster = true, region = false)
            DataProviderType.HTTP -> DataMode(raster = false, region = true)
        }
    }

    fun toggleExtendedMode() {
        dataMode = dataMode.copy(raster = dataMode.raster.xor(true))

        if (!isRealtime) {
            val dataChannels = HashSet<DataChannel>()
            dataChannels.add(DataChannel.STRIKES)
            updateData(dataChannels)
        }
    }

    val intervalDuration: Int
        get() = parameters.intervalDuration

    fun ffwdInterval(): Boolean {
        return updateParameters { it.ffwdInterval(history) }
    }

    fun rewInterval(): Boolean {
        return updateParameters { it.rewInterval(history) }
    }

    fun goRealtime(): Boolean {
        return updateParameters { it.goRealtime() }
    }

    fun invervalOffset(offset: Int, history: History): Boolean {
        return updateParameters { it.withIntervalOffset(offset, history) }
    }

    fun setPosition(position: Int): Boolean {
        return updateParameters { it.withPosition(position, history) }
    }

    private fun updateParameters(updater: (Parameters) -> Parameters): Boolean {
        val oldParameters = parameters
        parameters = updater.invoke(parameters)
        return parameters != oldParameters
    }

    val isRealtime: Boolean
        get() = parameters.isRealtime()

    private suspend fun toast(stringResource: Int) = withContext(Dispatchers.Main) {
        Toast.makeText(context, stringResource, Toast.LENGTH_LONG).show()
    }

    private fun broadcastEvent(event: DataEvent) {
        dataConsumerContainer.broadcast(event)
    }

    override fun run() {
        when (mode) {
            Mode.DATA -> {
                val currentTime = Period.currentTime
                val updateTargets = HashSet<DataChannel>()

                if (updatePeriod.shouldUpdate(currentTime, period)) {
                    updateTargets.add(DataChannel.STRIKES)
                }

                if (updateTargets.isNotEmpty()) {
                    updateData(updateTargets)
                }

                if (parameters.isRealtime()) {
                    val statusString = "" + updatePeriod.getCurrentUpdatePeriod(currentTime, period) + "/" + period
                    broadcastEvent(StatusEvent(statusString))
                    // Schedule the next update
                    handler.postDelayed(this, 1000)
                }
            }
            Mode.ANIMATION -> {
                parameters = parameters.animationStep(history)
                val delay = if (parameters.isRealtime()) 3000L else 100L
                handler.postDelayed(this, delay)
                updateUsingCache()
            }
        }
    }

    fun start() {
        mode = Mode.DATA
        if (isRealtime) {
            handler.post(this)
        }
    }

    fun startAnimation(history: History) {
        cache.clear()
        this.history = history
        mode = Mode.ANIMATION
        handler.post(this)
    }

    fun restart() {
        updatePeriod.restart()
        cache.clear()
        history = History()
        start()
    }

    fun stop() {
        handler.removeCallbacks(this)
    }

    companion object {
        val REQUEST_STARTED_EVENT = RequestStartedEvent()
        val DEFAULT_DATA_CHANNELS = setOf(DataChannel.STRIKES)
    }

    override fun onScroll(event: ScrollEvent?): Boolean {
        return false
    }

    override fun onZoom(event: ZoomEvent?): Boolean {
        return if (event != null && autoRaster) {
            val zoomLevel = event.zoomLevel
            autoRasterSizeUpdate(zoomLevel)
        } else {
            false
        }
    }

    private fun autoRasterSizeUpdate(zoomLevel: Double): Boolean {
        val rasterBaselength = when {
            zoomLevel >= 8f -> 5000
            zoomLevel in 4.5f..8f -> 10000
            zoomLevel in 3f..4.5f -> 25000
            zoomLevel in 2f..3f -> 50000
            else -> 100000
        }
        return if (parameters.rasterBaselength != rasterBaselength) {
            Log.v(
                Main.LOG_TAG,
                "MainDataHandler.autoRasterSizeUpdate() $zoomLevel : ${parameters.rasterBaselength} -> $rasterBaselength"
            )
            parameters = parameters.copy(rasterBaselength = rasterBaselength)
            updateData()
            true
        } else {
            false
        }
    }

    enum class Mode {
        DATA, ANIMATION
    }

}

internal const val DEFAULT_RASTER_BASELENGTH = 10000
internal const val AUTO_RASTER_BASELENGTH = "auto"

