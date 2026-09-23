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
 * A record ID tagged with the type of record it identifies.
 *
 * The type parameter keeps you from passing an `Id<Project>` to a lookup over todos. [Record.id] is
 * a plain `Long`, because typing it as `Id<Self>` would make every entity generic in itself, as in
 * `class Todo : Record<Todo>()`, and that type parameter would appear in every signature that
 * mentions a record, including the policy interface. The type is added back at the lookup, the only
 * place that needs it.
 *
 * @property value the ID.
 */
@JvmInline
public value class Id<T : Record>(public val value: Long) {
    override fun toString(): String = value.toString()
}

/**
 * The base class for a stored entity.
 *
 * A record is an ordinary Kotlin object that's also stored in the database. Its mutable fields are
 * [Cell]s, which are named snapshot state. Reading a field in a composable subscribes that
 * composable, and writing the field recomposes every session in the process that read it, with no
 * subscription code.
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
 * Each row corresponds to exactly one object. The identity map holds one instance for each type
 * and ID, and every lookup returns that instance, so equality is reference equality. [equals] and
 * [hashCode] are `final` so subclasses can't override them. Field-based equality, which a
 * `data class` entity would have, would break the identity map. [id] is assigned when the record is
 * constructed, not when it's inserted, so a new record can be used as a `key` before it's stored.
 *
 * For the same reason, don't put a record in `rememberSaved`. Session state is stored as JSON, and
 * deserializing a record would create a second object for a row that already has one in the
 * identity map. The copy wouldn't equal the original, wouldn't recompose readers when it changed,
 * and would never have passed an access check. Save the [id] instead, and look the record up again.
 * The lookup is policy-checked, so a resumed session that has lost access to the record gets `null`.
 */
public abstract class Record {

    /** The ID, which the loader can replace. See [adoptStoredId]. */
    private var assignedId: Long = Ids.next(this::class)

    /**
     * The record's ID, unique among records of the same concrete class.
     *
     * It's allocated at construction from a sequence for each class, shared by the whole process.
     * [Ids] explains why that's safe.
     */
    public val id: Long get() = assignedId

    /**
     * The database this record is stored in, or `null` if it isn't stored.
     *
     * This field has two uses. `todo.update { }` and `todo.delete()` use it to find the database to
     * open a transaction on, so the caller doesn't have to pass it. And `null` means "not stored yet,"
     * which is what [recordWrite] checks.
     */
    internal var database: Db? = null

    /**
     * The principals that obtained this record through the gate.
     *
     * It's filled in only while [LeakDetector] is on. It's a set because several principals can
     * legitimately obtain a shared record. The detector reports a read by a principal that never
     * obtained the record.
     */
    private var acquiredBy: MutableSet<Principal>? = null

    /** The stack trace of the first acquisition, which becomes the cause of [LeakDetected]. */
    private var acquiredAt: Throwable? = null

    /**
     * Replaces the ID assigned at construction with the ID stored in the database.
     *
     * Only the loader and [insertUnchecked] call this, before the record is added to the identity
     * map. Once anything else can see a record, its ID never changes.
     */
    internal fun adoptStoredId(stored: Long) {
        assignedId = stored
    }

    /** The cells that [register] added, by name. */
    private val mutableCells = LinkedHashMap<String, Cell<*>>()

    /**
     * This record's cells by name, in declaration order.
     *
     * The commit doesn't use this map. It goes through the table's columns instead, so that
     * immutable constructor properties are stored too. This map detects a subclass that declares a
     * column name twice, and tests and debugging use it to inspect a record's fields.
     */
    internal val cells: Map<String, Cell<*>> get() = mutableCells

    /**
     * Declares a stored field that holds a plain value.
     *
     * A column can't be declared as a constructor `var`, because the delegate has to own the state
     * for reads to be tracked.
     *
     * @param initial the initial value. It's usually the constructor parameter with the same name, as
     *   in `var title by column(title)`.
     */
    protected fun <V> column(initial: V): CellProvider<V> = CellProvider(initial)

    /**
     * Declares a stored reference to another record, which starts as `null`.
     *
     * It's stored as a foreign key and resolved to an object reference at load time, so following a
     * reference in application code is a field access, not a query.
     */
    protected fun <T : Record> reference(): CellProvider<T?> = CellProvider(null)

    /** Declares a stored, non-null reference to another record, starting at [initial]. */
    protected fun <T : Record> reference(initial: T): CellProvider<T> = CellProvider(initial)

    /**
     * Records that [principal] obtained this record through the gate.
     *
     * The gate calls this after every successful read check while [LeakDetector] is on. The set is
     * allocated only here, so records cost nothing extra when the detector is off.
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
     * Every cell read calls this while the detector is on. [LeakDetector] describes which leaks this
     * can and can't catch.
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
     * Adds [cell] to the current transaction's writes, so the change is committed to SQLite before
     * any composition can see it.
     *
     * If no transaction is open, the write is refused. Allowing it would change the field in memory
     * and on screen but not on disk, and nobody would notice until a restart lost the value. Records
     * that aren't stored yet are exempt, because there's nothing on disk for them to disagree with.
     *
     * @throws IllegalStateException if the record is stored and no transaction is open.
     */
    internal fun recordWrite(cell: Cell<*>) {
        if (database == null) return
        val writes = Transactions.current ?: error(
            "$cell was written outside a transaction. A change to stored state has to go through " +
                "db.transact { }, so that it is committed before any session can see it.",
        )
        writes.update(cell)
    }

    /** Adds [cell] to this record's cells, or throws if the name is taken. */
    internal fun <V> register(cell: Cell<V>): Cell<V> {
        require(mutableCells.put(cell.name, cell) == null) {
            "${this::class.simpleName} declares two columns named '${cell.name}'"
        }
        return cell
    }

    /** Returns whether [other] is this same object. See "Identity" above. */
    final override fun equals(other: Any?): Boolean = this === other

    final override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "${this::class.simpleName}#$id"
}

/**
 * One named field of a record's mutable state.
 *
 * A cell is snapshot state plus two things that storage needs and `mutableStateOf` doesn't have: a
 * name and an owning record. It knows nothing about SQL, connections, or tables, so the same type
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
     * The current value.
     *
     * Reading it subscribes the calling composable, and writing it invalidates every composable that
     * read it. Writing a stored record's cell outside a transaction throws [IllegalStateException].
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
 * This extra step exists only to get the property name. `column()` can't know which property it's
 * assigned to, but `provideDelegate` receives the property and can read its name.
 */
public class CellProvider<V> internal constructor(private val initial: V) {
    /** Creates the cell for [property] and registers it on [thisRef]. */
    public operator fun provideDelegate(thisRef: Record, property: KProperty<*>): Cell<V> =
        thisRef.register(Cell(thisRef, property.name, mutableStateOf(initial, LastWriteWins())))
}

/**
 * When two sessions write the same cell, keeps the value from the snapshot that applied last.
 *
 * Without a merge policy, concurrent writes to one cell conflict, and the second `apply()` throws.
 * By then, its transaction is already committed, because commits happen before the snapshot is
 * applied. Last-write-wins is the only policy that keeps memory consistent with the database.
 * Transactions run one at a time, so the snapshot that applies last also committed last, and its
 * value is the one on disk.
 *
 * Reporting the conflict instead would make a handler fail because someone else changed the same
 * record, after the database had already changed. If an application ever needs something like a
 * counter that adds instead of overwriting, a merge policy for each column would be the way to do it.
 */
private class LastWriteWins<V> : SnapshotMutationPolicy<V> {
    override fun equivalent(a: V, b: V): Boolean = a == b

    override fun merge(previous: V, current: V, applied: V): V = applied
}

/**
 * Allocates record IDs.
 *
 * There's one sequence per class for the whole process, not one per database. That works because a
 * process owns its database file exclusively: there's one in-memory graph, and another process
 * writing the file is an error that [Db] detects. Loading moves each sequence past the highest ID on
 * disk, so IDs allocated after a restart can't collide with stored ones.
 *
 * Tests that open several databases in one process share the sequences, so IDs have gaps. Nothing
 * relies on IDs being contiguous.
 */
internal object Ids {
    private val sequences = ConcurrentHashMap<KClass<*>, AtomicLong>()

    /** Returns the next ID for [type]. */
    fun next(type: KClass<*>): Long = sequenceFor(type).incrementAndGet()

    /**
     * Makes sure that every ID allocated from now on is greater than [id]. Loading calls it for
     * each row.
     */
    fun advanceTo(type: KClass<*>, id: Long) {
        sequenceFor(type).updateAndGet { current -> maxOf(current, id) }
    }

    /** Returns [type]'s sequence, creating it at `0` if needed. */
    private fun sequenceFor(type: KClass<*>): AtomicLong =
        sequences.computeIfAbsent(type) { AtomicLong(0) }
}
