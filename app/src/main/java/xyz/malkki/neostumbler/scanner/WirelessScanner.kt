package xyz.malkki.neostumbler.scanner

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import xyz.malkki.neostumbler.common.LocationWithSource
import xyz.malkki.neostumbler.domain.AirPressureObservation
import xyz.malkki.neostumbler.domain.BluetoothBeacon
import xyz.malkki.neostumbler.domain.CellTower
import xyz.malkki.neostumbler.domain.ObservedDevice
import xyz.malkki.neostumbler.domain.Position
import xyz.malkki.neostumbler.domain.WifiAccessPoint
import xyz.malkki.neostumbler.extensions.combineWithLatestFrom
import xyz.malkki.neostumbler.extensions.elapsedRealtimeMillisCompat
import xyz.malkki.neostumbler.scanner.data.ReportData
import xyz.malkki.neostumbler.scanner.movement.ConstantMovementDetector
import xyz.malkki.neostumbler.scanner.movement.MovementDetector
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

//Maximum accuracy for locations, used for filtering bad locations
private const val LOCATION_MAX_ACCURACY = 200

//Maximum age for locations
private val LOCATION_MAX_AGE = 20.seconds

//Maximum age for observed devices. This is used to filter out old data when e.g. there is no GPS signal and there's a gap between two locations
private val OBSERVED_DEVICE_MAX_AGE = 30.seconds

//Maximum age of air pressure data, relative to the location timestamp
private val AIR_PRESSURE_MAX_AGE = 2.seconds

/**
 * @param timeSource Time source used in the data, defaults to [SystemClock.elapsedRealtime]
 */
class WirelessScanner(
    private val locationSource: () -> Flow<LocationWithSource>,
    private val airPressureSource: () -> Flow<AirPressureObservation>,
    private val cellInfoSource: () -> Flow<List<CellTower>>,
    private val wifiAccessPointSource: () -> Flow<List<WifiAccessPoint>>,
    private val bluetoothBeaconSource: () -> Flow<List<BluetoothBeacon>>,
    private val movementDetector: MovementDetector = ConstantMovementDetector,
    private val timeSource: () -> Long = SystemClock::elapsedRealtime
) {
    fun createReports(): Flow<ReportData> = channelFlow {
        val mutex = Mutex()

        val wifiAccessPointByMacAddr = mutableMapOf<String, WifiAccessPoint>()
        val bluetoothBeaconsByMacAddr = mutableMapOf<String, BluetoothBeacon>()
        val cellTowersByKey = mutableMapOf<String, CellTower>()

        val isMovingFlow = movementDetector
            .getIsMovingFlow()
            .onEach {
                if (it) {
                    Timber.i("Moving started, resuming scanning")
                } else {
                    Timber.i("Moving stopped, pausing scanning")
                }
            }
            .shareIn(
                scope = this,
                started = SharingStarted.Eagerly,
                replay = 1
            )

        launch(Dispatchers.Default) {
            isMovingFlow
                .flatMapLatest { isMoving ->
                    if (isMoving) {
                        wifiAccessPointSource.invoke()
                    } else {
                        emptyFlow()
                    }
                }
                .map {
                    it.filterHiddenNetworks()
                }
                .collect { wifiAccessPoints ->
                    mutex.withLock {
                        wifiAccessPoints.forEach { wifiAccessPoint ->
                            wifiAccessPointByMacAddr[wifiAccessPoint.macAddress] = wifiAccessPoint
                        }
                    }
                }
        }

        launch(Dispatchers.Default) {
            isMovingFlow
                .flatMapLatest { isMoving ->
                    if (isMoving) {
                        bluetoothBeaconSource.invoke()
                    } else {
                        emptyFlow()
                    }
                }
                .collect { bluetoothBeacons ->
                    mutex.withLock {
                        bluetoothBeacons.forEach { bluetoothBeacon ->
                            bluetoothBeaconsByMacAddr[bluetoothBeacon.macAddress] = bluetoothBeacon
                        }
                    }
                }
        }

        launch(Dispatchers.Default) {
            isMovingFlow
                .flatMapLatest { isMoving ->
                    if (isMoving) {
                        cellInfoSource.invoke()
                    } else {
                        emptyFlow()
                    }
                }
                .collect { cellTowers ->
                    mutex.withLock {
                        cellTowers.forEach { cellTower ->
                            cellTowersByKey[cellTower.key] = cellTower
                        }
                    }
                }
        }

        isMovingFlow
            .flatMapLatest { isMoving ->
                if (isMoving) {
                    val locationFlow = locationSource.invoke()
                    val airPressureFlow = airPressureSource.invoke()

                    locationFlow.combineWithLatestFrom(airPressureFlow) { location, airPressure ->
                        //Use air pressure data only if it's not too old
                        location to airPressure?.takeIf {
                            abs(location.location.elapsedRealtimeMillisCompat - it.timestamp).milliseconds <= AIR_PRESSURE_MAX_AGE
                        }
                    }
                } else {
                    emptyFlow()
                }
            }
            .filter { (location, _) ->
                location.location.hasAccuracy() && location.location.accuracy <= LOCATION_MAX_ACCURACY
            }
            .filter { (location, _) ->
                val age = (timeSource.invoke() - location.location.elapsedRealtimeMillisCompat).milliseconds

                age <= LOCATION_MAX_AGE
            }
            //Collect locations in pairs so that we can choose the best one based on timestamp
            .runningFold(Pair<LocationWithAirPressure?, LocationWithAirPressure?>(null, null)) { pair, newLocation ->
                pair.second to newLocation
            }
            .filter {
                it.second != null
            }
            .flatMapConcat { (location1WithPressure, location2WithPressure) ->
                val (cells, wifis, bluetooths) = mutex.withLock {
                    val cells = cellTowersByKey.values.toList()
                    cellTowersByKey.clear()

                    val wifis = wifiAccessPointByMacAddr.values.toList()
                    wifiAccessPointByMacAddr.clear()

                    val bluetooths = bluetoothBeaconsByMacAddr.values.toList()
                    bluetoothBeaconsByMacAddr.clear()

                    Triple(cells, wifis, bluetooths)
                }

                val location1 = location1WithPressure?.first
                val location2 = location2WithPressure?.first

                val now = timeSource.invoke()

                val (location1cells, location2cells) = cells
                    .filterOldData(now)
                    .partitionByLocationTimestamp(location1?.location, location2!!.location)

                val (location1wifis, location2wifis) = wifis
                    .filterOldData(now)
                    .partitionByLocationTimestamp(location1?.location, location2.location)

                val (location1bluetooths, location2bluetooths) = bluetooths
                    .filterOldData(now)
                    .partitionByLocationTimestamp(location1?.location, location2.location)

                listOfNotNull(
                    location1?.let {
                        createReport(location1WithPressure, location1cells, location1wifis, location1bluetooths)
                    },
                    createReport(location2WithPressure, location2cells, location2wifis, location2bluetooths)
                ).asFlow()
            }
            .filter {
                it.bluetoothBeacons.isNotEmpty() || it.cellTowers.isNotEmpty() || it.wifiAccessPoints.isNotEmpty()
            }
            .collect(::send)
    }

    private val CellTower.key: String
        get() = listOf(mobileCountryCode, mobileNetworkCode, locationAreaCode, cellId, primaryScramblingCode).joinToString("/")

    private fun createReport(location: LocationWithAirPressure, cells: List<CellTower>, wifis: List<WifiAccessPoint>, bluetooths: List<BluetoothBeacon>): ReportData {
        return ReportData(
            position = Position.fromLocation(
                location = location.first.location,
                source = location.first.source.name.lowercase(Locale.ROOT),
                airPressure = location.second?.airPressure?.toDouble()
            ),
            cellTowers = cells,
            wifiAccessPoints = wifis.takeIf { it.size >= 2 } ?: emptyList(),
            bluetoothBeacons = bluetooths
        )
    }

    /**
     * Filters Wi-Fi networks that should not be sent to geolocation services, i.e.
     * hidden networks with empty SSID or those with SSID ending in "_nomap"
     *
     * @return Filtered list of scan results
     */
    private fun List<WifiAccessPoint>.filterHiddenNetworks(): List<WifiAccessPoint> = filter { wifiAccessPoint ->
        val ssid = wifiAccessPoint.ssid

        !ssid.isNullOrBlank() && !ssid.endsWith("_nomap")
    }

    private fun <T : ObservedDevice> List<T>.filterOldData(currentTimestamp: Long): List<T> = filter { device ->
        (currentTimestamp - device.timestamp).milliseconds <= OBSERVED_DEVICE_MAX_AGE
    }

    private fun <T : ObservedDevice> List<T>.partitionByLocationTimestamp(location1: Location?, location2: Location): Pair<List<T>, List<T>> = partition {
        //location1 can be null, because locations are collected pairwise and the first location does not have a pair
        location1 != null &&
                abs(it.timestamp - location1.elapsedRealtimeMillisCompat) < abs(it.timestamp - location2.elapsedRealtimeMillisCompat)
    }
}

private typealias LocationWithAirPressure = Pair<LocationWithSource, AirPressureObservation?>