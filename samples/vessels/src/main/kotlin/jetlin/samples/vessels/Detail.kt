package jetlin.samples.vessels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * The numbers on a vessel's page that move on their own.
 *
 * Everything else the detail page shows is looked up once and sits still. This is the part a router
 * actually reports second by second, and it is here to exercise the one thing the fleet list cannot:
 * an update with no client event behind it at all. Nothing subscribes — these are Compose state, so
 * writing one invalidates whatever read it and the applier records the difference.
 */
class Telemetry internal constructor(private val random: Random) {

    var downKbps: Int by mutableStateOf(random.nextInt(8, 40))
    var upKbps: Int by mutableStateOf(random.nextInt(20, 70))
    var latencyMs: Int by mutableStateOf(random.nextInt(18, 45))
    var obstructionPercent: Double by mutableStateOf(random.nextDouble(0.0, 0.4))
    var dishUptimeSeconds: Long by mutableStateOf(random.nextLong(3_600, 3_600 * 20))
    var cpuPercent: Int by mutableStateOf(random.nextInt(4, 30))
    var memoryPercent: Int by mutableStateOf(random.nextInt(18, 55))
    var rsrpDbm: Int by mutableStateOf(random.nextInt(-118, -95))
    var rsrqDb: Int by mutableStateOf(random.nextInt(-19, -8))
    var sinrDb: Int by mutableStateOf(random.nextInt(-2, 12))
    var satellites: Int by mutableStateOf(random.nextInt(14, 26))

    /** The last ten minutes of throughput, oldest first, as the bandwidth chart draws it. */
    var bandwidth: List<Sample> by mutableStateOf(
        List(BANDWIDTH_SAMPLES) { Sample(random.nextInt(0, 400), random.nextInt(0, 120)) },
    )

    /** Megabits per second, which is the unit the chart is labelled in. */
    val downMbps: Int get() = (bandwidth.last().downKbps / 1000.0).roundToInt()
    val upMbps: Int get() = (bandwidth.last().upKbps / 1000.0).roundToInt()

    /**
     * Moves everything on by one second.
     *
     * A plain function, not a coroutine and not wired to a clock, so a test can drive a minute of
     * telemetry in a microsecond and assert exactly what changed. Only the page wires it to time.
     */
    fun tick() {
        downKbps = walk(downKbps, 4, 2, 90)
        upKbps = walk(upKbps, 6, 8, 140)
        latencyMs = walk(latencyMs, 3, 14, 80)
        cpuPercent = walk(cpuPercent, 3, 2, 96)
        memoryPercent = walk(memoryPercent, 2, 12, 92)
        rsrpDbm = walk(rsrpDbm, 2, -125, -80)
        rsrqDb = walk(rsrqDb, 1, -20, -6)
        sinrDb = walk(sinrDb, 1, -4, 20)
        satellites = walk(satellites, 1, 8, 31)
        obstructionPercent = (obstructionPercent + random.nextDouble(-0.03, 0.03)).coerceIn(0.0, 2.5)
        dishUptimeSeconds += 1

        // A rolling window: one sample on, the oldest off, so the chart scrolls rather than redraws.
        val spike = if (random.nextInt(20) == 0) random.nextInt(4_000, 12_000) else 0
        bandwidth = bandwidth.drop(1) + Sample(
            downKbps = (bandwidth.last().downKbps + random.nextInt(-150, 200) + spike).coerceIn(0, 12_000),
            upKbps = (bandwidth.last().upKbps + random.nextInt(-60, 80)).coerceIn(0, 3_000),
        )
    }

    private fun walk(value: Int, step: Int, min: Int, max: Int): Int =
        (value + random.nextInt(-step, step + 1)).coerceIn(min, max)

    class Sample(val downKbps: Int, val upKbps: Int)

    internal companion object {
        const val BANDWIDTH_SAMPLES = 40
    }
}

/** One card in the CONNECTIONS block. */
class Connection(
    val title: String,
    val state: State,
    val rows: List<Pair<String, String>>,
    val pills: List<String> = emptyList(),
)

/** Whether a connection's dot is green, amber or grey. */
enum class State { UP, DEGRADED, IDLE }

/** A row in ROUTING or VLANS: a name, the VLAN it is on, and a network. */
class NetRow(val name: String, val vlan: Int, val gateway: String, val cidr: String)

/** A port chip in NOTES & PORTS: its label (`W1`, `1`, …) and its link state. */
class Port(val label: String, val state: PortState)

/** Whether a port's chip is green (link up), red (enabled but down) or grey (disabled). */
enum class PortState { LINK_UP, ENABLED, DISABLED }

/** The SPEEDFUSION VPN block: a peer and the tunnels under it. */
class Peer(val name: String, val links: List<Link>) {
    val connected: Int get() = links.count { it.connected }
    class Link(val name: String, val connected: Boolean, val active: Boolean, val remote: String, val subnets: String)
}

/** A licence in NOTES & PORTS, with the relative date the original prints beside it. */
class Licence(val name: String, val granted: String, val relative: String)

/** A month's worth of usage, split by the WAN it went over. */
class MonthUsage(val label: String, val wanGb: Double, val cellularGb: Double, val days: List<DayUsage>) {
    val totalGb: Double get() = wanGb + cellularGb
    class DayUsage(val label: String, val wanGb: Double, val cellularGb: Double)
}

/** An hour's usage, for the three-day bar chart. */
class HourUsage(val label: String, val wanGb: Double, val cellularGb: Double)

/**
 * Everything the detail page looks up once and then leaves alone.
 *
 * Deliberately one object fetched by one call, because that is the shape of the thing it stands in
 * for — the original's page awaits several server functions and shows loading states while they
 * answer. Splitting it into a dozen getters would have made the page tidier and would have removed
 * the loading state, which is the part worth having.
 */
class VesselDetail(
    val model: String,
    val identity: List<Pair<String, String>>,
    val service: List<Pair<String, String>>,
    val tags: List<String>,
    val note: String?,
    val ports: List<Port>,
    val licences: List<Licence>,
    val connections: List<Connection>,
    val starlink: List<Pair<String, String>>,
    val routing: List<NetRow>,
    val vlans: List<NetRow>,
    val peer: Peer,
    val geofences: List<String>,
    val position: Pair<Double, Double>,
    val months: List<MonthUsage>,
    val hours: List<HourUsage>,
)

private val telemetryByVessel = ConcurrentHashMap<String, Telemetry>()

/**
 * The live numbers for [vessel], created on first ask and kept afterwards.
 *
 * Process-wide rather than per session, like the rest of this fake backend, so two windows watching
 * one vessel see the same readings — which is what they would see if these came off a real router.
 */
fun FleetStore.telemetry(vessel: Vessel): Telemetry =
    telemetryByVessel.getOrPut(vessel.id) { Telemetry(Random(vessel.id.hashCode())) }

/** Advances one vessel's telemetry. Called by the open page once a second, and by tests directly. */
fun FleetStore.tick(vessel: Vessel) {
    telemetry(vessel).tick()
}

/** Forgets every vessel's telemetry, so a test starts from the seeded readings. */
fun FleetStore.resetTelemetry() {
    telemetryByVessel.clear()
}

/**
 * Stands in for the calls the detail page makes that leave the process.
 *
 * The delay is the point. The original awaits Peplink and Starlink and shows loading states while
 * they answer; a fake that returned instantly would render a page that never has one, and the
 * loading state is a thing worth being able to test.
 */
suspend fun FleetStore.detail(vessel: Vessel): VesselDetail {
    delay(600)
    return describe(vessel)
}

/** The same data without the wait, for tests that are not about the loading state. */
fun FleetStore.describe(vessel: Vessel): VesselDetail {
    val random = Random(vessel.id.hashCode())
    val device = deviceStatus(vessel)
    val lanPrefix = vessel.lanIp.substringBeforeLast('.')
    val online = device?.online == true

    val networks = listOf(
        NetRow("", 0, vessel.lanIp, "$lanPrefix.0/24"),
        NetRow("CYGN-MANAGEMENT", 10, "$lanPrefix.10.1", "$lanPrefix.10.0/24"),
        NetRow("CREW", 20, "$lanPrefix.20.1", "$lanPrefix.20.0/24"),
        NetRow("CLIENT", 30, "$lanPrefix.30.1", "$lanPrefix.30.0/24"),
        NetRow("GUEST", 31, "$lanPrefix.31.1", "$lanPrefix.31.0/24"),
        NetRow("IOT", 40, "$lanPrefix.40.1", "$lanPrefix.40.0/24"),
        NetRow("OPERATION", 50, "$lanPrefix.50.1", "$lanPrefix.50.0/24"),
        NetRow("STORAGE", 60, "$lanPrefix.60.1", "$lanPrefix.60.0/24"),
        NetRow("VOIP", 70, "$lanPrefix.70.1", "$lanPrefix.70.0/24"),
    )

    val publicIp = "81.170.${random.nextInt(100, 200)}.${random.nextInt(2, 250)}"
    val wanDown = random.nextDouble(0.4, 24.0)
    val wanUp = random.nextDouble(0.1, 4.0)
    val cellDown = random.nextDouble(0.1, 6.0)
    val cellUp = random.nextDouble(0.05, 1.2)
    val carrier = listOf(
        "Telekom Deutschland GER Norlys", "Telenor NOR", "Elisa FIN", "Vodafone NL",
    ).random(random)

    return VesselDetail(
        model = "Peplink MAX Transit Pro E (HW2,3)",
        identity = listOfNotNull(
            "Model" to "Peplink MAX Transit Pro E (HW2,3)",
            "Serial" to vessel.serial,
            "Firmware" to "8.6.0 build ${random.nextInt(6000, 6999)}",
            "Uptime" to (device?.let { formatUptime(it.uptimeSeconds) } ?: "—"),
            "Clients" to (device?.clients?.toString() ?: "—"),
            "First appeared" to "over 1 year ago",
            "Last config applied" to "about ${random.nextInt(2, 11)} hours ago",
        ),
        service = listOf(
            "Last online" to if (online) "Now" else "${random.nextInt(2, 40)} days ago",
            "PrimeCare expiry" to "2026-${"%02d".format(random.nextInt(1, 13))}-${"%02d".format(random.nextInt(1, 28))}",
            "Product code" to "MAX-TST-PROE-5GN-T-PRM",
            "Hardware revision" to "2",
            "LAN MAC" to (0..5).joinToString(":") { "%02X".format(random.nextInt(256)) },
            "Public IP" to publicIp,
            "Site ID" to "NOS-${vessel.name.substringBefore(' ')}",
        ),
        tags = listOf("map-marker-yacht", "#star", "GEO", "Offshore", "TRANSIT", "api", "GEOFENCE", "sea"),
        note = if (random.nextInt(3) == 0) "Crew reported intermittent VOIP drops on the bridge." else null,
        ports = listOf(
            Port("W1", PortState.LINK_UP),
            Port("1", PortState.LINK_UP),
            Port("2", PortState.ENABLED),
            Port("3", PortState.DISABLED),
        ),
        licences = listOf(
            Licence("SpeedFusion Cloud Activation by Sales Registration", "2025-11-17", "10 months ago"),
            Licence(
                "SpeedFusion Cloud Activation by Standard Warranty (SFC-CLD-C)",
                "2025-02-07",
                "over 1 year ago",
            ),
        ),
        connections = listOf(
            Connection(
                title = "WAN",
                state = if (online) State.UP else State.IDLE,
                rows = listOf(
                    "Status" to if (online) "Connected via Starlink" else "Disconnected",
                    "IP" to "150.228.${random.nextInt(1, 250)}.${random.nextInt(1, 250)}",
                    "Data (this month)" to "↓ ${formatGb(wanDown)} · ↑ ${formatGb(wanUp)}",
                    "Details" to "IP configuration",
                ),
            ),
            Connection(
                title = "Cellular",
                state = if (online) State.UP else State.DEGRADED,
                pills = listOf("Roaming", "5G"),
                rows = listOf(
                    "Status" to "Connected to $carrier",
                    "Carrier" to carrier,
                    "IP" to "100.86.${random.nextInt(1, 250)}.${random.nextInt(1, 250)}",
                    "Data (this month)" to "↓ ${formatGb(cellDown)} · ↑ ${formatGb(cellUp)}",
                ),
            ),
            Connection(
                title = "Wi-Fi WAN on 2.4 GHz",
                state = State.IDLE,
                rows = listOf(
                    "Status" to "Scanning...",
                    "Data (this month)" to "↓ 0 MB · ↑ 0 MB",
                    "Details" to "IP configuration",
                ),
            ),
        ),
        starlink = emptyList(), // filled from live telemetry; see the STARLINK DISH panel
        routing = networks,
        vlans = networks,
        peer = Peer(
            name = "conn_to_SDX",
            links = listOf(
                Peer.Link("Port", connected = true, active = true, remote = "SDX", subnets = "192.168.65.0/24, ${publicIp.substringBeforeLast('.')}.96/27"),
                Peer.Link("Offshore", connected = true, active = false, remote = "SDX", subnets = "192.168.65.0/24, ${publicIp.substringBeforeLast('.')}.96/27"),
            ),
        ),
        geofences = listOf("Offshore", "TRANSIT", "sea"),
        position = random.nextDouble(54.0, 60.0) to random.nextDouble(8.0, 16.0),
        months = MONTH_LABELS.map { label ->
            val monthRandom = Random((vessel.id + label).hashCode())
            val days = (1..3).map { day ->
                MonthUsage.DayUsage(
                    label = "${label.substringBefore(' ')} $day",
                    wanGb = monthRandom.nextDouble(0.0, 9.0),
                    cellularGb = monthRandom.nextDouble(0.0, 3.0),
                )
            }
            MonthUsage(label, days.sumOf { it.wanGb }, days.sumOf { it.cellularGb }, days)
        },
        hours = (0 until 72).map { hour ->
            val hourRandom = Random((vessel.id + "h" + hour).hashCode())
            val busy = hour % 24 in 8..20
            HourUsage(
                label = "%02d:00".format(hour % 24),
                wanGb = if (busy) hourRandom.nextDouble(0.0, 2.4) else hourRandom.nextDouble(0.0, 0.3),
                cellularGb = if (busy) hourRandom.nextDouble(0.0, 1.4) else hourRandom.nextDouble(0.0, 0.2),
            )
        },
    )
}

internal val MONTH_LABELS = listOf("Apr 2026", "May 2026", "Jun 2026", "Jul 2026", "Aug 2026", "Sep 2026")
