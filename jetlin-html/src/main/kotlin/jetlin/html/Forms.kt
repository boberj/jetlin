package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
    initial: T,
    private val validate: (T) -> String?,
) {
    public var value: T by mutableStateOf(initial)

    public var touched: Boolean by mutableStateOf(false)
        private set

    /** Validation message to show, or null while the field is untouched or valid. */
    public val error: String? get() = if (touched) validate(value) else null

    /** Whether the current value passes validation, regardless of whether it has been touched. */
    public val isValid: Boolean get() = validate(value) == null

    /** Records an edit. Callers using [bind] get this for free. */
    public fun edit(newValue: T) {
        value = newValue
        touched = true
    }

    /** Returns to a pristine state, e.g. after a successful submit. */
    public fun reset(newValue: T) {
        value = newValue
        touched = false
    }
}

/**
 * Remembers a form field across recompositions.
 *
 * [validate] returns the message to show, or null when the value is acceptable. It runs on the
 * server on every read of [Field.error], so it may consult whatever it needs.
 */
@Composable
public fun <T> rememberField(initial: T, validate: (T) -> String? = { null }): Field<T> =
    remember { Field(initial, validate) }

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
