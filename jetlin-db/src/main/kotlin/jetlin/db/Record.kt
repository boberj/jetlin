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
 * A record id tagged with the type of record it identifies.
 *
 * The type parameter prevents passing an `Id<Project>` to a lookup over todos. [Record.id] is a plain
 * `Long` because typing it as `Id<Self>` would require every entity to be generic in itself
 * (`class Todo : Record<Todo>()`), and that type parameter would then appear in every signature that
 * mentions a record, including the policy interface. The type is added back at the lookup, which is
 * the only place it is needed.
 */
@JvmInline
public value class Id<T : Record>(public val value: Long) {
    override fun toString(): String = value.toString()
}

/**
 * Base class for a persistent entity.
 *
 * A record is an ordinary Kotlin object that is also stored in the database. Its mutable fields are
 * [Cell]s, which are named snapshot state. Reading a field in a composable subscribes that composable,
 * and writing the field recomposes every session in the process that read it. No subscription code is
 * needed.
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
 * Each row corresponds to exactly one object. The identity map holds one instance per (type, id), and
 * every lookup returns that instance, so equality is reference equality. [equals] and [hashCode] are
 * `final` so subclasses can't override them: field-based equality, which a `data class` entity would
 * have, would break the identity map. [id] is assigned when the record is constructed, not when it is
 * inserted, so a new record can be used as a `key` before it is stored.
 *
 * The same rule is why a record must not be put in `rememberSaved`. Session state is stored as JSON,
 * and deserializing a record would create a second object for a row that already has one in the
 * identity map. The copy would not be equal to the original, would not recompose readers when it
 * changed, and would never have passed an access check. Save the [id] instead and look the record up
 * again. The lookup is policy-checked, so a resumed session that has lost access to the record gets
 * null.
 */
public abstract class Record {

    private var assignedId: Long = Ids.next(this::class)

    /**
     * The record's id, unique among records of the same concrete class.
     *
     * Allocated at construction from a per-class, process-wide sequence. [Ids] explains why that is
     * safe.
     */
    public val id: Long get() = assignedId

    /**
     * The database this record is stored in, or null if it hasn't been stored.
     *
     * This field has two uses. `todo.update { }` and `todo.delete()` use it to find the database to
     * open a transaction on, so the caller doesn't have to pass it. And a null value means "not stored
     * yet", which is what the write hook checks. There used to be a separate `stored` flag, but it was
     * always equal to `database != null`.
     */
    internal var database: Db? = null

    /**
     * The principals that obtained this record through the gate, and the stack trace of the first
     * acquisition.
     *
     * Only populated while [LeakDetector] is enabled. It is a set because a shared record is
     * legitimately obtained by several principals. What the detector reports is a read by a principal
     * that never obtained the record.
     */
    private var acquiredBy: MutableSet<Principal>? = null
    private var acquiredAt: Throwable? = null

    /**
     * Replaces the constructor-assigned id with the id stored in the database.
     *
     * Only the loader calls this, and only before the record is added to the identity map. Once
     * anything else can see a record, its id never changes.
     */
    internal fun adoptStoredId(stored: Long) {
        assignedId = stored
    }

    private val mutableCells = LinkedHashMap<String, Cell<*>>()

    /**
     * This record's cells by name, in declaration order.
     *
     * The flush does not use this map. It goes through the table's columns instead, so that immutable
     * constructor properties are stored as well. This map is used to detect a subclass declaring a
     * column name twice, and by tests and debugging to inspect a record's fields.
     */
    internal val cells: Map<String, Cell<*>> get() = mutableCells

    /**
     * Declares a persistent field holding a plain value.
     *
     * [initial] is usually the constructor parameter with the same name, as in
     * `var title by column(title)`. A column can't be declared as a constructor `var` because the
     * delegate has to own the state for reads to be tracked.
     */
    protected fun <V> column(initial: V): CellProvider<V> = CellProvider(initial)

    /**
     * Declares a persistent, initially null reference to another record.
     *
     * Stored as a foreign key and resolved to an object reference at load time, so following a
     * reference in application code is a field access, not a query.
     */
    protected fun <T : Record> reference(): CellProvider<T?> = CellProvider(null)

    /** Declares a persistent, non-null reference to another record. */
    protected fun <T : Record> reference(initial: T): CellProvider<T> = CellProvider(initial)

    /**
     * Records that [principal] obtained this record through the gate.
     *
     * The gate calls this after every successful read check while [LeakDetector] is enabled. The set is
     * only allocated here, so records cost nothing extra when the detector is off.
     */
    internal fun recordAcquisition(principal: Principal) {
        val principals = acquiredBy ?: Collections.newSetFromMap(IdentityHashMap<Principal, Boolean>())
            .also { acquiredBy = it }
        if (principals.add(principal) && acquiredAt == null) {
            acquiredAt = Throwable("$this was acquired here, for $principal")
        }
    }

    /**
     * Throws [LeakDetected] if the current thread's principal never obtained this record.
     *
     * Called on every cell read while the detector is enabled. [LeakDetector] describes which leaks
     * this can and cannot catch.
     */
    internal fun checkRead() {
        val reader = CurrentPrincipal.current ?: return
        val principals = acquiredBy ?: return
        if (reader !in principals) {
            throw LeakDetected(
                "$this is being read by $reader, which never obtained it — it was obtained by " +
                    principals.joinToString() + ". A record reached a principal without passing the gate: " +
                    "something cached it, captured it in a closure, or held it past the request that " +
                    "acquired it. The cause of this exception is the stack where it was acquired.",
                acquiredAt,
            )
        }
    }

    /**
     * Adds [cell] to the current transaction's writes, so the change is committed to SQLite before any
     * composition can see it.
     *
     * If no transaction is open, the write is refused. Allowing it would change the field in memory
     * and on screen but not on disk, and nobody would notice until a restart lost the value. Records
     * that haven't been stored yet are exempt, since there is nothing on disk for them to disagree
     * with.
     */
    internal fun recordWrite(cell: Cell<*>) {
        if (database == null) return
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
 * A single named field of a record's mutable state.
 *
 * A cell is snapshot state plus two things persistence needs and `mutableStateOf` doesn't have: a
 * name and an owning record. It has no knowledge of SQL, connections or tables, so the same type
 * would work for a value fetched from an external API.
 */
public class Cell<V> internal constructor(
    /** The record this cell belongs to. */
    public val record: Record,
    /** The property name, which is also the column name. */
    public val name: String,
    private val state: MutableState<V>,
) : ReadWriteProperty<Record, V> {

    /**
     * The current value. Reading it subscribes the calling composition, and writing it invalidates
     * every composition that read it.
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
 * Creates the [Cell] for `by column(x)` and registers it on the declaring record.
 *
 * This extra step exists only to get the property name. `column()` itself has no way of knowing
 * which property it is assigned to; `provideDelegate` receives the property and can read its name.
 */
public class CellProvider<V> internal constructor(private val initial: V) {
    public operator fun provideDelegate(thisRef: Record, property: KProperty<*>): Cell<V> =
        thisRef.register(Cell(thisRef, property.name, mutableStateOf(initial, LastWriteWins())))
}

/**
 * When two sessions write the same cell, keeps the value from the snapshot that applied last.
 *
 * Without a merge policy, concurrent writes to one cell conflict and the second `apply()` throws. By
 * then its transaction has already been committed, because commits happen before the snapshot is
 * applied. Last-write-wins is the only policy that keeps memory consistent with the database:
 * transactions are serialized, so the snapshot that applies last also committed last, and its value
 * is the one on disk.
 *
 * Reporting the conflict instead would make a handler fail because someone else touched the same
 * record, after the database had already changed. If an application ever needs something like a
 * counter that adds instead of overwriting, a per-column merge policy would be the way to add it.
 */
private class LastWriteWins<V> : SnapshotMutationPolicy<V> {
    override fun equivalent(a: V, b: V): Boolean = a == b

    override fun merge(previous: V, current: V, applied: V): V = applied
}

/**
 * Allocates record ids.
 *
 * There is one sequence per class for the whole process, not one per database. That works because a
 * process has exclusive ownership of its database file: there is one resident graph, and another
 * process writing the file is treated as an error to detect. Loading moves each sequence past the
 * highest id on disk, so ids allocated after a restart can't collide with stored ones.
 *
 * Tests that open several databases in one process share the sequences, so ids have gaps. Nothing
 * relies on ids being contiguous.
 */
internal object Ids {
    private val sequences = ConcurrentHashMap<KClass<*>, AtomicLong>()

    fun next(type: KClass<*>): Long = sequenceFor(type).incrementAndGet()

    /** Makes sure every id allocated from now on is greater than [id]. Called for each row loaded at boot. */
    fun advanceTo(type: KClass<*>, id: Long) {
        sequenceFor(type).updateAndGet { current -> maxOf(current, id) }
    }

    private fun sequenceFor(type: KClass<*>): AtomicLong =
        sequences.computeIfAbsent(type) { AtomicLong(0) }
}
