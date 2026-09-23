package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import jetlin.runtime.rememberSaved
import kotlinx.serialization.builtins.serializer

/**
 * One editable value, whether it's valid, and whether the user has edited it yet.
 *
 * The authoritative copy of the value lives here, on the server. The browser shows a rendered copy
 * and reports edits. That's why validation can call anything, such as a database or another
 * service, without an API in between.
 *
 * [touched] keeps a form from opening covered in errors. A field that has never been edited reports
 * no [error], even when it's invalid, while [isValid] always reflects the real state.
 */
public class Field<T> internal constructor(
    private val state: MutableState<T>,
    private val touchedState: MutableState<Boolean>,
    private val validate: (T) -> String?,
) {
    /** The current value. Setting it doesn't mark the field as touched. To do that, call [edit]. */
    public var value: T
        get() = state.value
        set(newValue) { state.value = newValue }

    /** Whether the user has edited the field since it was created or last [reset]. */
    public val touched: Boolean get() = touchedState.value

    /** The validation message to show, or `null` if the field is untouched or valid. */
    public val error: String? get() = if (touched) validate(value) else null

    /** Whether the current value passes validation, whether or not the field was touched. */
    public val isValid: Boolean get() = validate(value) == null

    /** Sets the value and marks the field as touched. [bind] calls this for you. */
    public fun edit(newValue: T) {
        state.value = newValue
        touchedState.value = true
    }

    /** Sets the value and marks the field as untouched, for example after a successful submit. */
    public fun reset(newValue: T) {
        state.value = newValue
        touchedState.value = false
    }
}

/**
 * Remembers a form field across recompositions.
 *
 * The `Field` wrapper is rebuilt on each pass while its state is remembered, so [validate] is
 * always the lambda from the current composition, not one captured on the first.
 *
 * @param initial the value when the field is first composed.
 * @param validate returns the message to show, or `null` if the value is acceptable. It runs on the
 *   server on every read of [Field.error] and [Field.isValid], so it can check anything it needs.
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
 * Use it for input that's worth more than it costs to store, such as a half-written message or a
 * long form partly filled in, so a dropped connection or a deployment doesn't throw away what the
 * user typed. [Field.touched] is deliberately not saved: a restored form should show the text again,
 * not the errors.
 *
 * @param initial the value when nothing was saved.
 * @param key the key the value is saved under. See [rememberSaved].
 * @param validate returns the message to show, or `null` if the value is acceptable.
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
 * Binds a text input to [field]. The input shows the field's value and reports edits to it.
 *
 * @param debounceMs how long, in milliseconds, typing must pause before the client sends the value.
 *   It trades how quickly validation reacts against how many round trips typing costs.
 */
public fun AttrsScope.bind(field: Field<String>, debounceMs: Int = 150) {
    value(field.value)
    onInput(debounceMs) { field.edit(it) }
}

/** Returns whether every field passes validation. Use it to enable a submit button. */
public fun allValid(vararg fields: Field<*>): Boolean = fields.all { it.isValid }
