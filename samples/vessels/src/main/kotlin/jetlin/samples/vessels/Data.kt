package jetlin.samples.vessels

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/**
 * A vessel as the fleet list needs it.
 *
 * Field for field this is what the original's `getHomeData` selects: the vessel row, the five status
 * flags and two numbers from its status row, and three counts joined on afterwards. Kept faithful
 * even where a field is only used for one badge, because the point of the exercise is to render what
 * a real page renders rather than a convenient subset of it.
 *
 * The mutable fields are Compose state, so a flag toggled anywhere reaches every session looking at
 * this vessel without anything subscribing to anything.
 */
class Vessel(
    val id: String,
    val name: String,
    val peplinkGroupId: Int?,
    val peplinkMainDeviceId: Int?,
    val starlinkDataSource: String?,
    val kvhTerminalId: String?,
    val starlinkServiceLineNumber: String?,
    alert: Boolean = false,
    construction: Boolean = false,
    maintenance: Boolean = false,
    disabled: Boolean = false,
    emergency: Boolean = false,
    priority: Int = 0,
    progress: Int = 0,
    val openTicketCount: Int = 0,
    val noteCount: Int = 0,
    val attachmentCount: Int = 0,
) {
    var alert: Boolean by mutableStateOf(alert)
    var construction: Boolean by mutableStateOf(construction)
    var maintenance: Boolean by mutableStateOf(maintenance)
    var disabled: Boolean by mutableStateOf(disabled)
    var emergency: Boolean by mutableStateOf(emergency)
    var priority: Int by mutableStateOf(priority)
    var progress: Int by mutableStateOf(progress)

    /**
     * The device serial and LAN gateway the fleet list prints in small type under the name.
     *
     * Derived from the id rather than stored, so they are stable across a restart without a table to
     * keep them in. The shape follows the real hardware's: a batch number, then two hex groups.
     */
    val serial: String
        get() {
            val random = Random(id.hashCode())
            val batch = listOf(1931, 1933, 1936).random(random)
            fun group() = (0..3).joinToString("") { "0123456789ABCDEF"[random.nextInt(16)].toString() }
            return "$batch-${group()}-${group()}"
        }

    val lanIp: String
        get() {
            val random = Random(id.hashCode() * 31)
            // A handful of vessels sit on a 192.168 network instead, as they do in the original.
            return if (random.nextInt(12) == 0) "192.168.${random.nextInt(1, 200)}.1"
            else "10.${random.nextInt(50, 120)}.0.1"
        }

    /**
     * Ordering by severity, applied before whatever column the user sorted by.
     *
     * Straight from the original: an emergency vessel stays at the top of the table whatever the
     * sort is, and a disabled one stays at the bottom. Sorting only decides the order *within* a
     * severity band.
     */
    val statusRank: Int
        get() = when {
            emergency -> 0
            alert -> 1
            construction -> 2
            maintenance -> 3
            disabled -> 5
            else -> 4
        }

    val statusLabel: String
        get() = when {
            emergency -> "Emergency"
            alert -> "Alert"
            construction -> "Construction"
            maintenance -> "Maintenance"
            disabled -> "Disabled"
            else -> "Normal"
        }
}

/** The five flags an operator can set on a vessel, named as the original names them. */
enum class VesselFlag(val label: String) {
    CONSTRUCTION("Construction"),
    MAINTENANCE("Maintenance"),
    ALERT("Alert"),
    EMERGENCY("Emergency"),
    DISABLED("Disable"),
}

/** Which column the fleet table is sorted by. */
enum class SortKey(val param: String) {
    NAME("name"),
    USAGE("usage"),
    PROGRESS("progress"),
    PRIORITY("priority"),
    ;

    companion object {
        fun from(value: String?): SortKey = entries.firstOrNull { it.param == value } ?: PRIORITY
    }
}

/** What the original reads from Peplink, per device: whether it is up, and how it is doing. */
class DeviceStatus(val online: Boolean, val uptimeSeconds: Long, val clients: Int)

/** What the original reads from Starlink or KVH, per vessel: usage against an allowance. */
class UsageStatus(val usedGb: Double, val planGb: Double) {
    val fraction: Double get() = if (planGb <= 0) 0.0 else (usedGb / planGb).coerceIn(0.0, 1.0)
    val level: String get() = when {
        fraction >= 0.9 -> "critical"
        fraction >= 0.7 -> "warning"
        else -> "ok"
    }
}

/**
 * Stands in for the database and the three external services the real pages call.
 *
 * Deliberately shaped like the API rather than like the view: `list(...)` filters and sorts here
 * rather than handing the page a collection to sift through itself.
 *
 * It returns everything that matches, with no paging and no windowing. The original virtualizes the
 * table instead — 52-pixel rows, eight of overscan — which a server cannot do, because a server
 * cannot see the scroll position. So this renders all of it, and the cost of that is a number in
 * FINDINGS.md rather than an argument.
 */
object FleetStore {

    val organizationName: String = "Northern Offshore Services"

    private val vessels = mutableStateListOf<Vessel>()

    init {
        reset()
    }

    fun find(id: String): Vessel? = vessels.firstOrNull { it.id == id }

    val size: Int get() = vessels.size

    /** Counts for the header, over the whole fleet rather than the current page. */
    fun onlineCount(): Int = vessels.count { deviceStatus(it)?.online == true }
    fun offlineCount(): Int = vessels.count { deviceStatus(it)?.online == false }
    fun unknownCount(): Int = vessels.size - onlineCount() - offlineCount()

    /**
     * One page of the fleet, filtered and sorted server-side.
     *
     * Severity leads, as in the original — `statusRank` before the chosen column — so an emergency
     * stays visible whatever anybody sorted by. Name breaks every remaining tie, which keeps the
     * order stable and therefore keeps the keyed rows meaningful across a re-sort.
     */
    fun list(query: String, sort: SortKey, ascending: Boolean): List<Vessel> {
        val needle = query.trim().lowercase()
        val matching = if (needle.isEmpty()) {
            vessels.toList()
        } else {
            vessels.filter { it.name.lowercase().contains(needle) }
        }

        val direction = if (ascending) 1 else -1
        val sorted = matching.sortedWith(
            compareBy<Vessel> { it.statusRank }
                .thenBy { vessel ->
                    when (sort) {
                        SortKey.NAME -> 0
                        SortKey.USAGE -> (usage(vessel)?.usedGb ?: 0.0) * direction
                        SortKey.PROGRESS -> vessel.progress.toDouble() * direction
                        SortKey.PRIORITY -> vessel.priority.toDouble() * direction
                    }
                }
                .thenBy { if (sort == SortKey.NAME && direction < 0) "" else it.name }
                .thenByDescending { if (sort == SortKey.NAME && direction < 0) it.name else "" },
        )

        return sorted
    }

    fun toggle(vessel: Vessel, flag: VesselFlag) {
        when (flag) {
            VesselFlag.CONSTRUCTION -> vessel.construction = !vessel.construction
            VesselFlag.MAINTENANCE -> vessel.maintenance = !vessel.maintenance
            VesselFlag.ALERT -> vessel.alert = !vessel.alert
            VesselFlag.EMERGENCY -> vessel.emergency = !vessel.emergency
            VesselFlag.DISABLED -> vessel.disabled = !vessel.disabled
        }
    }

    fun isSet(vessel: Vessel, flag: VesselFlag): Boolean = when (flag) {
        VesselFlag.CONSTRUCTION -> vessel.construction
        VesselFlag.MAINTENANCE -> vessel.maintenance
        VesselFlag.ALERT -> vessel.alert
        VesselFlag.EMERGENCY -> vessel.emergency
        VesselFlag.DISABLED -> vessel.disabled
    }

    /**
     * Canned Peplink device status.
     *
     * Derived from the vessel id rather than stored, so it is stable across a reload without a
     * table to keep it in — a fake of a read-only external call, which is what it stands in for.
     */
    fun deviceStatus(vessel: Vessel): DeviceStatus? {
        val deviceId = vessel.peplinkMainDeviceId ?: return null
        val random = Random(deviceId)
        val online = random.nextInt(10) > 1
        return DeviceStatus(
            online = online,
            uptimeSeconds = if (online) random.nextLong(3_600, 3_600 * 24 * 90) else 0,
            clients = if (online) random.nextInt(1, 60) else 0,
        )
    }

    /** Canned Starlink or KVH usage, likewise derived rather than stored. */
    fun usage(vessel: Vessel): UsageStatus? {
        if (vessel.starlinkDataSource == null && vessel.kvhTerminalId == null) return null
        val random = Random(vessel.id.hashCode())
        val plan = listOf(50.0, 100.0, 150.0, 250.0).random(random)
        return UsageStatus(usedGb = random.nextDouble(0.5, plan * 0.55), planGb = plan)
    }

    /**
     * Canned cellular (5G) usage, in GB — its own figure rather than a fraction of the Starlink one,
     * since the fleet list's dotted meter is driven by absolute GB. Present only for a vessel with a
     * Peplink device, as the original's is summed from that device's cellular WANs.
     */
    fun cellularUsageGb(vessel: Vessel): Double? {
        val deviceId = vessel.peplinkMainDeviceId ?: return null
        val random = Random(deviceId * 7)
        return random.nextDouble(5.0, 640.0)
    }

    /** Puts the fleet back to its seeded state, so browser tests start from a known list. */
    fun reset() {
        vessels.clear()
        val random = Random(20260902)
        SEED_NAMES.forEachIndexed { index, name ->
            val hasPeplink = index % 7 != 3
            vessels += Vessel(
                id = "v${(index + 1).toString().padStart(3, '0')}",
                name = name,
                peplinkGroupId = if (hasPeplink) 4000 + index else null,
                peplinkMainDeviceId = if (hasPeplink) 90_000 + index else null,
                starlinkDataSource = if (index % 9 != 4) "starlink" else null,
                kvhTerminalId = if (index % 9 == 4) "KVH-${8000 + index}" else null,
                starlinkServiceLineNumber = if (index % 9 != 4) "SL-${500_000 + index}" else null,
                alert = index % 31 == 0,
                construction = index % 5 == 0,
                maintenance = index % 9 == 0,
                disabled = index % 29 == 0,
                emergency = index % 53 == 0,
                priority = random.nextInt(0, 6),
                progress = listOf(0, 15, 30, 45, 60, 75, 90, 100).random(random),
                openTicketCount = if (index % 4 == 0) random.nextInt(1, 9) else 0,
                noteCount = if (index % 3 == 0) random.nextInt(1, 12) else 0,
                attachmentCount = if (index % 5 == 0) random.nextInt(1, 4) else 0,
            )
        }
    }
}

internal fun formatUptime(seconds: Long): String {
    if (seconds <= 0) return "—"
    val days = seconds / 86_400
    val hours = (seconds % 86_400) / 3_600
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${(seconds % 3_600) / 60}m"
        else -> "${seconds / 60}m"
    }
}

internal fun formatGb(gb: Double): String = when {
    gb >= 1000 -> "%.1f TB".format(gb / 1000)
    gb >= 10 -> "%.0f GB".format(gb)
    else -> "%.1f GB".format(gb)
}

private val SEED_NAMES = listOf(
    "Aurora Borealis", "Baltic Trader", "Cape Endeavour", "Coral Sea", "Delta Star",
    "Eastern Dawn", "Fjord Explorer", "Gulf Pioneer", "Harbour Light", "Iron Duke",
    "Juno Spirit", "Kestrel", "Lady Constance", "Marina Bay", "Nordic Voyager",
    "Ocean Sentinel", "Pacific Crest", "Quantum Leap", "Regal Princess", "Sea Falcon",
    "Thunder Bay", "Umberto", "Valiant", "Western Isles", "Xanadu",
    "Yankee Clipper", "Zephyr", "Arctic Fox", "Bright Horizon", "Celtic Mist",
    "Dover Castle", "Emerald Isle", "Fortuna", "Golden Gate", "Highland Chief",
    "Islay Mist", "Jade Dragon", "Kraken", "Loch Ness", "Meridian",
    "Northern Light", "Orion", "Polaris", "Queen Mab", "Rising Sun",
    "Southern Cross", "Trade Wind", "Ursa Major", "Vega", "White Rose",
    "Xebec", "York Minster", "Zenith", "Albatross", "Bosun's Call",
    "Cormorant", "Dolphin", "Eagle Ray", "Firefly", "Gannet",
    "Heron", "Ibis", "Jackdaw", "Kingfisher", "Lapwing",
    "Merlin", "Nightjar", "Osprey", "Petrel", "Quail",
    "Raven", "Sandpiper", "Tern", "Umbrella Bird", "Vulture",
    "Warbler", "Xantus", "Yellowhammer", "Zebra Finch", "Anchor Point",
)
