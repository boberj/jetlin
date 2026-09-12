package jetlin.db

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.mutableStateOf
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * The identity of a record, carrying the kind of record it names.
 *
 * The type parameter is the point: it is what stops a `Id<Project>` reaching a lookup over todos.
 * [Record.id] itself is a plain `Long`, because typing it as `Id<Self>` would mean making every
 * entity generic in itself — `class Todo : Record<Todo>()` — which leaks into every signature that
 * mentions a record, including the policy interface. The type is therefore recovered where it is
 * useful — at the lookup — rather than carried everywhere it is not.
 */
@JvmInline
public value class Id<T : Record>(public val value: Long) {
    override fun toString(): String = value.toString()
}

/**
 * Base class for a persistent entity.
 *
 * A record is an ordinary Kotlin object that happens to be durable. Its mutable state lives in
 * [Cell]s — snapshot state with a name — so reading a field from a composable subscribes that
 * composable, and writing it recomposes every session that had read it, in this process, with no
 * subscription bookkeeping anywhere.
 *
 * ```kotlin
 * class Todo(val owner: User, title: String) : Record() {
 *     var title by column(title)
 *     var done by column(false)
 *     var project: Project? by reference()
 * }
 * ```
 *
 * ## Identity
 *
 * One row is one object: the identity map holds a single instance per (type, id) and everything that
 * obtains a record obtains that instance. Equality is therefore reference equality, and it is `final`
 * so that a subclass cannot redefine it — a record whose `equals` compared fields would break the
 * identity map, and a `data class` entity would do exactly that. [id] is assigned at construction
 * rather than at insert, so a record is usable as a `key` before it has been stored.
 */
public abstract class Record {

    private var assignedId: Long = Ids.next(this::class)

    /**
     * Stable identity, unique among records of this concrete class.
     *
     * Allocated at construction from a process-wide sequence per class; see [Ids] for why that is
     * sound.
     */
    public val id: Long get() = assignedId

    /**
     * Whether this record exists on disk.
     *
     * The write hook consults it: a record that has not been stored yet needs no dirty tracking,
     * because the insert that stores it carries whatever its fields end up holding.
     */
    internal var stored: Boolean = false

    /**
     * The database holding this record, once it is stored.
     *
     * What lets `todo.update { }` and `todo.delete()` find a transaction to run in without the caller
     * passing a database they already stored the record in.
     */
    internal var database: Db? = null

    /**
     * Viewers that obtained this record through the gate, and where the first of them did it.
     *
     * Only populated while [LeakDetector] is on. An identity set rather than a single viewer because a
     * shared row is legitimately acquired by many: what is not legitimate is a read under a viewer that
     * never acquired it at all.
     */
    private var acquiredBy: MutableSet<Principal>? = null
    private var acquiredAt: Throwable? = null

    /**
     * Reapplies the id a stored row was written with.
     *
     * Only the loader calls this, and only before the record becomes resident: a record's id is fixed
     * from the point anything can see it.
     */
    internal fun adoptStoredId(stored: Long) {
        assignedId = stored
    }

    private val mutableCells = LinkedHashMap<String, Cell<*>>()

    /**
     * This record's named mutable state, in declaration order.
     *
     * Not what the flush reads — that goes through the table's columns, so that an immutable
     * constructor property is stored too. This is the record's own account of itself, which is what
     * catches a subclass shadowing a column name and what a test or a debugger asks when it wants to
     * know what a record is made of.
     */
    internal val cells: Map<String, Cell<*>> get() = mutableCells

    /**
     * Declares a persistent field holding a plain value.
     *
     * The [initial] value is normally the constructor parameter of the same name, which is why a
     * column is declared as `var title by column(title)` rather than as a constructor property: the
     * delegate has to own the state for reads to be trackable.
     */
    protected fun <V> column(initial: V): CellProvider<V> = CellProvider(initial)

    /**
     * Declares a persistent reference to another record, starting out absent.
     *
     * Persisted as a foreign key and resolved back to an object reference at load, so traversal in
     * application code is a pointer dereference rather than a query.
     */
    protected fun <T : Record> reference(): CellProvider<T?> = CellProvider(null)

    /** Declares a persistent reference that always points at something. */
    protected fun <T : Record> reference(initial: T): CellProvider<T> = CellProvider(initial)

    /**
     * Records that [cell] is about to change, so that the change reaches SQLite before it reaches any
     * composition.
     *
     * Refusing the write when no transaction is open is deliberate: the alternative is a field that
     * changes on screen and not on disk, which is invisible until a restart. A record that is not
     * stored yet is exempt — nothing is out of step with disk, because it is not on disk.
     */
    internal fun recordAcquisition(viewer: Principal) {
        val viewers = acquiredBy ?: Collections.newSetFromMap(IdentityHashMap<Principal, Boolean>())
            .also { acquiredBy = it }
        if (viewers.add(viewer) && acquiredAt == null) {
            acquiredAt = Throwable("$this was acquired here, for $viewer")
        }
    }

    /**
     * Fails if the thread's current viewer never obtained this record.
     *
     * Called from every cell read while the detector is on; see [LeakDetector] for what it can and
     * cannot see.
     */
    internal fun checkRead() {
        val reader = CurrentViewer.current ?: return
        val viewers = acquiredBy ?: return
        if (reader !in viewers) {
            throw LeakDetected(
                "$this is being read by $reader, which never obtained it — it was obtained by " +
                    viewers.joinToString() + ". A record reached a viewer without passing the gate: " +
                    "something cached it, captured it in a closure, or held it past the request that " +
                    "acquired it. The cause of this exception is the stack where it was acquired.",
                acquiredAt,
            )
        }
    }

    internal fun recordWrite(cell: Cell<*>) {
        if (!stored) return
        val writes = Transactions.current ?: error(
            "$cell was written outside a transaction. A change to stored state has to go through " +
                "db.transact { }, so that it is committed before any session can see it.",
        )
        writes.update(cell)
    }

    internal fun <V> register(cell: Cell<V>): Cell<V> {
        require(mutableCells.put(cell.name, cell) == null) {
            "${this::class.simpleName} declares two columns named '${cell.name}'"
        }
        return cell
    }

    final override fun equals(other: Any?): Boolean = this === other

    final override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "${this::class.simpleName}#$id"
}

/**
 * One named piece of a record's mutable state.
 *
 * A cell is snapshot state plus the two things persistence needs that `mutableStateOf` does not
 * carry: a name, and the record it belongs to. Nothing here knows about SQL, a connection or a
 * table — a cell is equally the right shape for a value fetched from an external API.
 */
public class Cell<V> internal constructor(
    /** The record this cell belongs to. */
    public val record: Record,
    /** The property name, which is also the column name. */
    public val name: String,
    private val state: MutableState<V>,
) : ReadWriteProperty<Record, V> {

    /**
     * The current value. Reading subscribes the calling composition; writing invalidates every
     * composition that had read it.
     */
    public var value: V
        get() {
            if (LeakDetector.enabled) record.checkRead()
            return state.value
        }
        set(value) {
            record.recordWrite(this)
            state.value = value
        }

    override fun getValue(thisRef: Record, property: KProperty<*>): V = value

    override fun setValue(thisRef: Record, property: KProperty<*>, value: V) {
        this.value = value
    }

    override fun toString(): String = "$record.$name"
}

/**
 * Turns `by column(x)` into a [Cell] registered on the record declaring it.
 *
 * The indirection exists only to learn the property name: a delegate created by `column()` has no
 * way to know what it is called until `provideDelegate` is handed the property.
 */
public class CellProvider<V> internal constructor(private val initial: V) {
    public operator fun provideDelegate(thisRef: Record, property: KProperty<*>): Cell<V> =
        thisRef.register(Cell(thisRef, property.name, mutableStateOf(initial, LastWriteWins())))
}

/**
 * Resolves two sessions writing one cell in favour of whichever applied last.
 *
 * Without a merge policy a concurrent write is a conflict, and the losing `apply()` throws — after its
 * commit has already happened, because this design commits before applying. Last-write-wins is
 * therefore not a preference but the only answer consistent with disk: transactions are serialized, so
 * the snapshot applied last is the one whose commit landed last, and taking its value is what keeps
 * memory equal to the file.
 *
 * Surfacing the conflict instead would mean a handler failing on a row someone else happened to touch,
 * with the database already changed. Per-column merge policies are the natural extension if an
 * application ever needs counters to add rather than overwrite.
 */
private class LastWriteWins<V> : SnapshotMutationPolicy<V> {
    override fun equivalent(a: V, b: V): Boolean = a == b

    override fun merge(previous: V, current: V, applied: V): V = applied
}

/**
 * Allocates record ids.
 *
 * Process-wide per class rather than per database, because a process owns its database file
 * exclusively (there is one resident graph, and an outside writer is an error to detect rather than a
 * case to support). Loading advances the sequence past the highest id on disk, so ids allocated
 * before and after a restart cannot collide.
 *
 * A test that opens several databases in one process shares the sequence with them, which only means
 * ids skip — nothing depends on them being dense.
 */
internal object Ids {
    private val sequences = ConcurrentHashMap<KClass<*>, AtomicLong>()

    fun next(type: KClass<*>): Long = sequenceFor(type).incrementAndGet()

    /** Ensures ids handed out from now on are above [id]. Called for every row read at boot. */
    fun advanceTo(type: KClass<*>, id: Long) {
        sequenceFor(type).updateAndGet { current -> maxOf(current, id) }
    }

    private fun sequenceFor(type: KClass<*>): AtomicLong =
        sequences.computeIfAbsent(type) { AtomicLong(0) }
}
