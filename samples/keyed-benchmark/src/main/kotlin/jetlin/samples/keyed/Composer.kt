package jetlin.samples.keyed

import androidx.compose.runtime.ComposeRuntimeFlags
import androidx.compose.runtime.ExperimentalComposeApi

/**
 * Chooses between Compose's two slot-table implementations, from `COMPOSER=gap|link`.
 *
 * Since 1.12 the runtime carries two composers: the gap buffer every earlier Compose used, and a
 * link buffer that is off by default and marked experimental. `CompositionImpl` reads the flag when
 * it is constructed, so this has to run before the first session is opened — which is why it is
 * called at the top of each entry point rather than from anywhere a session happens to be built.
 */
@OptIn(ExperimentalComposeApi::class)
internal fun selectComposer() {
    when (val choice = System.getenv("COMPOSER")) {
        null, "gap" -> ComposeRuntimeFlags.isLinkBufferComposerEnabled = false
        "link" -> ComposeRuntimeFlags.isLinkBufferComposerEnabled = true
        else -> error("COMPOSER must be 'gap' or 'link', not '$choice'")
    }
}
