package jetlin.samples.vessels

import androidx.compose.runtime.Composable
import jetlin.html.AttrsScope
import jetlin.html.Circle
import jetlin.html.Line
import jetlin.html.Path
import jetlin.html.Rect
import jetlin.html.Svg

/**
 * The icons the original uses, from lucide (ISC licensed), so the shapes match rather than
 * approximate them.
 *
 * Each entry is that icon's inner elements, encoded as `tag;attr=value;attr=value` with `|` between
 * elements. Path data is opaque however it is written, so it is stored compactly and parsed once
 * rather than spelled out as a hundred lines of constructor calls. Neither `;` nor `|` occurs in SVG
 * path data, so the encoding is unambiguous.
 *
 * Regenerated from `lucide-static` when an icon is added; nothing reads it at runtime but this file.
 */
internal enum class Icon(private val encoded: String) {
    ALERT_CIRCLE("circle;cx=12;cy=12;r=10|line;x1=12;x2=12;y1=8;y2=12|line;x1=12;x2=12.01;y1=16;y2=16"),
    ALERT_TRIANGLE("path;d=m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3|path;d=M12 9v4|path;d=M12 17h.01"),
    ANCHOR("path;d=M12 6v16|path;d=m19 13 2-1a9 9 0 0 1-18 0l2 1|path;d=M9 11h6|circle;cx=12;cy=4;r=2"),
    ARROW_LEFT("path;d=m12 19-7-7 7-7|path;d=M19 12H5"),
    BELL("path;d=M10.268 21a2 2 0 0 0 3.464 0|path;d=M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"),
    CHECK("path;d=M20 6 9 17l-5-5"),
    CHEVRON_DOWN("path;d=m6 9 6 6 6-6"),
    CHEVRON_RIGHT("path;d=m9 18 6-6-6-6"),
    CHEVRON_UP("path;d=m18 15-6-6-6 6"),
    CLIPBOARD_LIST("rect;width=8;height=4;x=8;y=2;rx=1;ry=1|path;d=M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2|path;d=M12 11h4|path;d=M12 16h4|path;d=M8 11h.01|path;d=M8 16h.01"),
    CONSTRUCTION("rect;x=2;y=6;width=20;height=8;rx=1|path;d=M17 14v7|path;d=M7 14v7|path;d=M17 3v3|path;d=M7 3v3|path;d=M10 14 2.3 6.3|path;d=m14 6 7.7 7.7|path;d=m8 6 8 8"),
    CPU("path;d=M12 20v2|path;d=M12 2v2|path;d=M17 20v2|path;d=M17 2v2|path;d=M2 12h2|path;d=M2 17h2|path;d=M2 7h2|path;d=M20 12h2|path;d=M20 17h2|path;d=M20 7h2|path;d=M7 20v2|path;d=M7 2v2|rect;x=4;y=4;width=16;height=16;rx=2|rect;x=8;y=8;width=8;height=8;rx=1"),
    EXTERNAL_LINK("path;d=M15 3h6v6|path;d=M10 14 21 3|path;d=M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"),
    EYE_OFF("path;d=M10.733 5.076a10.744 10.744 0 0 1 11.205 6.575 1 1 0 0 1 0 .696 10.747 10.747 0 0 1-1.444 2.49|path;d=M14.084 14.158a3 3 0 0 1-4.242-4.242|path;d=M17.479 17.499a10.75 10.75 0 0 1-15.417-5.151 1 1 0 0 1 0-.696 10.75 10.75 0 0 1 4.446-5.143|path;d=m2 2 20 20"),
    LAYOUT_GRID("rect;width=7;height=7;x=3;y=3;rx=1|rect;width=7;height=7;x=14;y=3;rx=1|rect;width=7;height=7;x=14;y=14;rx=1|rect;width=7;height=7;x=3;y=14;rx=1"),
    MAP("path;d=M14.106 5.553a2 2 0 0 0 1.788 0l3.659-1.83A1 1 0 0 1 21 4.619v12.764a1 1 0 0 1-.553.894l-4.553 2.277a2 2 0 0 1-1.788 0l-4.212-2.106a2 2 0 0 0-1.788 0l-3.659 1.83A1 1 0 0 1 3 19.381V6.618a1 1 0 0 1 .553-.894l4.553-2.277a2 2 0 0 1 1.788 0z|path;d=M15 5.764v15|path;d=M9 3.236v15"),
    MAP_PIN("path;d=M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0|circle;cx=12;cy=10;r=3"),
    MEMORY_STICK("path;d=M12 12v-2|path;d=M12 18v-2|path;d=M16 12v-2|path;d=M16 18v-2|path;d=M2 11h1.5|path;d=M20 18v-2|path;d=M20.5 11H22|path;d=M4 18v-2|path;d=M8 12v-2|path;d=M8 18v-2|rect;x=2;y=6;width=20;height=10;rx=2"),
    NOTEBOOK_TEXT("path;d=M2 6h4|path;d=M2 10h4|path;d=M2 14h4|path;d=M2 18h4|rect;width=16;height=20;x=4;y=2;rx=2|path;d=M9.5 8h5|path;d=M9.5 12H16|path;d=M9.5 16H14"),
    PENCIL("path;d=M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z|path;d=m15 5 4 4"),
    PHONE("path;d=M13.832 16.568a1 1 0 0 0 1.213-.303l.355-.465A2 2 0 0 1 17 15h3a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2A18 18 0 0 1 2 4a2 2 0 0 1 2-2h3a2 2 0 0 1 2 2v3a2 2 0 0 1-.8 1.6l-.468.351a1 1 0 0 0-.292 1.233 14 14 0 0 0 6.392 6.384"),
    SATELLITE("path;d=m13.5 6.5-3.148-3.148a1.205 1.205 0 0 0-1.704 0L6.352 5.648a1.205 1.205 0 0 0 0 1.704L9.5 10.5|path;d=M16.5 7.5 19 5|path;d=m17.5 10.5 3.148 3.148a1.205 1.205 0 0 1 0 1.704l-2.296 2.296a1.205 1.205 0 0 1-1.704 0L13.5 14.5|path;d=M9 21a6 6 0 0 0-6-6|path;d=M9.352 10.648a1.205 1.205 0 0 0 0 1.704l2.296 2.296a1.205 1.205 0 0 0 1.704 0l4.296-4.296a1.205 1.205 0 0 0 0-1.704l-2.296-2.296a1.205 1.205 0 0 0-1.704 0z"),
    SCROLL_TEXT("path;d=M15 12h-5|path;d=M15 8h-5|path;d=M19 17V5a2 2 0 0 0-2-2H4|path;d=M8 21h12a2 2 0 0 0 2-2v-1a1 1 0 0 0-1-1H11a1 1 0 0 0-1 1v1a2 2 0 1 1-4 0V5a2 2 0 1 0-4 0v2a1 1 0 0 0 1 1h3"),
    SEARCH("path;d=m21 21-4.34-4.34|circle;cx=11;cy=11;r=8"),
    SETTINGS("path;d=M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915|circle;cx=12;cy=12;r=3"),
    SHIP("path;d=M12 2v2|path;d=M12 9.189V13|path;d=M19 12V6a2 2 0 00-2-2H7a2 2 0 00-2 2v6|path;d=M19.38 19A11.6 11.6 0 0021 13l-8.188-3.639a2 2 0 00-1.624 0L3 13.001a11.6 11.6 0 002.81 7.76|path;d=M2 20c.6.5 1.2 1 2.5 1 2.5 0 2.5-2 5-2 1.3 0 1.9.5 2.5 1s1.2 1 2.5 1c2.5 0 2.5-2 5-2 1.3 0 1.9.5 2.5 1"),
    WAVES("path;d=M2 12q2.5 2 5 0t5 0 5 0 5 0|path;d=M2 19q2.5 2 5 0t5 0 5 0 5 0|path;d=M2 5q2.5 2 5 0t5 0 5 0 5 0"),
    WRENCH("path;d=M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z"),
    ;

    internal val shapes: List<Shape> by lazy {
        encoded.split('|').map { element ->
            val parts = element.split(';')
            Shape(parts[0], parts.drop(1).map { it.substringBefore('=') to it.substringAfter('=') })
        }
    }

    internal class Shape(val tag: String, val attrs: List<Pair<String, String>>)
}

/**
 * Draws [icon] at whatever size [classes] says.
 *
 * Stroke rather than fill, and `currentColor`, so an icon takes its colour from the text around it
 * the way the original's do — `text-red-600` on the wrapper is all it takes.
 *
 * Pass [classes] a literal: Tailwind reads these class names out of this source file, and one built
 * at runtime is not there to be read.
 */
@Composable
internal fun Icon(icon: Icon, classes: String = "h-4 w-4") {
    Svg({
        classes(classes)
        attr("viewBox", "0 0 24 24")
        attr("fill", "none")
        attr("stroke", "currentColor")
        attr("stroke-width", "2")
        attr("stroke-linecap", "round")
        attr("stroke-linejoin", "round")
        attr("aria-hidden", "true")
    }) {
        for (shape in icon.shapes) {
            val apply: AttrsScope.() -> Unit = { shape.attrs.forEach { (name, value) -> attr(name, value) } }
            when (shape.tag) {
                "path" -> Path(apply)
                "circle" -> Circle(apply)
                "rect" -> Rect(apply)
                "line" -> Line(apply)
                else -> error("Icon ${icon.name} uses an unhandled SVG element <${shape.tag}>")
            }
        }
    }
}
