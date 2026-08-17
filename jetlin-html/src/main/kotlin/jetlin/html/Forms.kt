package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import jetlin.runtime.rememberSaved
import kotlinx.serialization.builtins.serializer

/**
 * One editable value, its validity, and whether the user has interacted with it yet.
 *
 * The authoritative copy of the value lives here, on the server. The browser holds a rendered
 * reflection of it and reports edits, which is why validation can call into anything — a database,
 * another service — without an API in between.
 *
 * [touched] exists so a form does not open covered in errors: a field that has never been edited
 * reports no [error] even when it is invalid, while [isValid] always reflects the real state.
 */
public class Field<T> internal constructor(
    private val state: MutableState<T>,
    private val touchedState: MutableState<Boolean>,
    private val validate: (T) -> String?,
) {
    public var value: T
        get() = state.value
        set(newValue) { state.value = newValue }

    public val touched: Boolean get() = touchedState.value

    /** Validation message to show, or null while the field is untouched or valid. */
    public val error: String? get() = if (touched) validate(value) else null

    /** Whether the current value passes validation, regardless of whether it has been touched. */
    public val isValid: Boolean get() = validate(value) == null

    /** Records an edit. Callers using [bind] get this for free. */
    public fun edit(newValue: T) {
        state.value = newValue
        touchedState.value = true
    }

    /** Returns to a pristine state, e.g. after a successful submit. */
    public fun reset(newValue: T) {
        state.value = newValue
        touchedState.value = false
    }
}

/**
 * Remembers a form field across recompositions.
 *
 * [validate] returns the message to show, or null when the value is acceptable. It runs on the
 * server on every read of [Field.error], so it may consult whatever it needs.
 *
 * The `Field` wrapper is rebuilt on each pass while its state is remembered, so [validate] is
 * always the lambda from the current composition rather than one captured on the first.
 */
@Composable
public fun <T> rememberField(initial: T, validate: (T) -> String? = { null }): Field<T> =
    Field(
        state = remember { mutableStateOf(initial) },
        touchedState = remember { mutableStateOf(false) },
        validate = validate,
    )

/**
 * A form field whose value survives the session hibernating.
 *
 * For input worth more than it costs to store — a half-written message, a long form partly filled
 * in — so that a dropped connection or a deploy does not throw the user's typing away. [touched] is
 * deliberately not saved: a restored form should show the text again, not the errors.
 */
@Composable
public fun rememberSavedField(
    initial: String,
    key: String? = null,
    validate: (String) -> String? = { null },
): Field<String> = Field(
    state = rememberSaved(String.serializer(), key) { initial },
    touchedState = remember { mutableStateOf(false) },
    validate = validate,
)

/**
 * Binds a text input to [field]: renders the current value and reports edits back.
 *
 * [debounceMs] is the tradeoff between how quickly validation reacts and how many round trips
 * typing costs; the client holds the keystrokes and sends one event per quiet period.
 */
public fun AttrsScope.bind(field: Field<String>, debounceMs: Int = 150) {
    value(field.value)
    onInput(debounceMs) { field.edit(it) }
}

/** True when every field passes validation. Convenient as a submit button's enabled test. */
public fun allValid(vararg fields: Field<*>): Boolean = fields.all { it.isValid }
