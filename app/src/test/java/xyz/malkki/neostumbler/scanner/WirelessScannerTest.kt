package xyz.malkki.neostumbler.scanner

import android.location.Location
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import xyz.malkki.neostumbler.common.LocationWithSource
import xyz.malkki.neostumbler.domain.AirPressureObservation
import xyz.malkki.neostumbler.domain.BluetoothBeacon
import xyz.malkki.neostumbler.domain.WifiAccessPoint
import xyz.malkki.neostumbler.scanner.data.ReportData
import kotlin.time.Duration.Companion.seconds

class WirelessScannerTest {
    @Test
    fun `Test no reports are created with no data`() {
        val wirelessScanner = WirelessScanner(
            locationSource = {
                flowOf(
                    LocationWithSource(
                        source = LocationWithSource.LocationSource.GPS,
                        location = mock<Location> {
                            on { provider } doReturn "gps"
                            on { latitude } doReturn 50.0
                            on { longitude } doReturn 10.0
                            on { accuracy } doReturn 15.0f
                            on { elapsedRealtimeMillis } doReturn 0
                        }
                    )
                )
            },
            cellInfoSource = { emptyFlow() },
            wifiAccessPointSource = { emptyFlow() },
            bluetoothBeaconSource = { emptyFlow() },
            airPressureSource = { emptyFlow() }
        )

        val reportFlow = wirelessScanner.createReports()

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(1.seconds) {
                    reportFlow.first()
                }
            }
        }
    }

    @Test
    fun `Test no reports are created with old data`() {
        val wirelessScanner = WirelessScanner(
            locationSource = {
                flowOf(
                    LocationWithSource(
                        source = LocationWithSource.LocationSource.GPS,
                        location = mock<Location> {
                            on { provider } doReturn "gps"
                            on { latitude } doReturn 50.0
                            on { longitude } doReturn 10.0
                            on { accuracy } doReturn 15.0f
                            on { elapsedRealtimeMillis } doReturn 60000
                        }
                    )
                )
            },
            cellInfoSource = { emptyFlow() },
            wifiAccessPointSource = { emptyFlow() },
            bluetoothBeaconSource = {
                flowOf(listOf(
                    BluetoothBeacon(
                        macAddress = "01:01:01:01:01",
                        signalStrength = -68,
                        timestamp = 25000,
                        beaconType = null,
                        id1 = null,
                        id2 = null,
                        id3 = null,
                    )
                ))
            },
            airPressureSource = { emptyFlow() }
        )

        val reportFlow = wirelessScanner.createReports()

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(1.seconds) {
                    reportFlow.first()
                }
            }
        }
    }

    @Test
    fun `Test no reports are created when Wi-Fi networks have an opt-out`() {
        val wirelessScanner = WirelessScanner(
            locationSource = {
                flowOf(
                    LocationWithSource(
                        source = LocationWithSource.LocationSource.GPS,
                        location = mock<Location> {
                            on { provider } doReturn "gps"
                            on { latitude } doReturn 50.0
                            on { longitude } doReturn 10.0
                            on { accuracy } doReturn 15.0f
                            on { elapsedRealtimeMillis } doReturn 0
                        }
                    )
                )
            },
            cellInfoSource = { emptyFlow() },
            wifiAccessPointSource = {
                flowOf(listOf(
                    WifiAccessPoint(
                        macAddress = "01:01:01:01:01:01",
                        radioType = WifiAccessPoint.RadioType.AC,
                        channel = null,
                        frequency = null,
                        signalStrength = null,
                        ssid = "test_nomap",
                        timestamp = 0
                    ),
                    WifiAccessPoint(
                        macAddress = "02:02:02:02:02:02",
                        radioType = WifiAccessPoint.RadioType.AC,
                        channel = null,
                        frequency = null,
                        signalStrength = null,
                        ssid = "",
                        timestamp = 0
                    )
                ))
            },
            bluetoothBeaconSource = { emptyFlow() },
            airPressureSource = { emptyFlow() }
        )

        val reportFlow = wirelessScanner.createReports()

        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking {
                withTimeout(1.seconds) {
                    reportFlow.first()
                }
            }
        }
    }

    @Test
    fun `Test that a report is created`() {
        val wirelessScanner = WirelessScanner(
            locationSource = {
                flow {
                    delay(1000)
                    emit(
                        LocationWithSource(
                            source = LocationWithSource.LocationSource.GPS,
                            location = mock<Location> {
                                on { provider } doReturn "gps"
                                on { latitude } doReturn 50.0
                                on { longitude } doReturn 10.0
                                on { accuracy } doReturn 15.0f
                                on { elapsedRealtimeMillis } doReturn 0
                                on { hasAccuracy() } doReturn true
                            }
                        )
                    )
                }
            },
            cellInfoSource = { emptyFlow() },
            wifiAccessPointSource = { emptyFlow() },
            bluetoothBeaconSource = {
                flowOf(listOf(
                    BluetoothBeacon(
                        macAddress = "01:01:01:01:01:01",
                        beaconType = null,
                        id1 = null,
                        id2 = null,
                        id3 = null,
                        signalStrength = -75,
                        timestamp = 0
                    )
                ))
            },
            airPressureSource = { flowOf(AirPressureObservation(airPressure = 1013.25f, timestamp = 0L)) },
            timeSource = { 0 }
        )

        val reportFlow = wirelessScanner.createReports()

        val reports = runBlocking {
            val output = mutableListOf<ReportData>()

            reportFlow
                .timeout(5.seconds)
                .catch {
                    if (it !is TimeoutCancellationException) {
                        throw it
                    }
                }
                .collect(output::add)

            output.toList()
        }

        assertEquals(1, reports.size)

        val report = reports.first()

        assertNotNull(report.position.pressure)
        assertEquals(1013.25, report.position.pressure!!, 0.01)
    }
}