package com.app.finance.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The app's entire icon set, drawn as inline vectors.
 *
 * `androidx.compose.material:material-icons-extended` is roughly 3 MB of
 * unshrinkable resources against a 6 MB APK ceiling, and even
 * `material-icons-core` carries hundreds of glyphs to supply the five used
 * here. 04 §2.5 requires written justification for anything over 300 KB; five
 * hand-built paths need none.
 *
 * Each is a rectangle-based mark, which is also the right register: a ledger is
 * made of rules and columns, and these read as ruled marks rather than as
 * generic app furniture.
 */
object DayBookIcons {

    /** Dashboard — a page with a hero figure and rules beneath it. */
    val Dashboard: ImageVector by lazy {
        icon("dashboard") {
            bar(4f, 4f, 10f, 5f) // the hero figure
            bar(4f, 12f, 16f, 2f)
            bar(4f, 16f, 16f, 2f)
            bar(4f, 20f, 10f, 2f)
        }
    }

    /** Ledger — four ruled lines of equal weight. */
    val Ledger: ImageVector by lazy {
        icon("ledger") {
            bar(3f, 5f, 18f, 2f)
            bar(3f, 10f, 18f, 2f)
            bar(3f, 15f, 18f, 2f)
            bar(3f, 20f, 12f, 2f)
        }
    }

    /** Income — bars rising left to right, the lumpy-income series. */
    val Income: ImageVector by lazy {
        icon("income") {
            bar(3f, 15f, 3f, 6f)
            bar(8f, 10f, 3f, 11f)
            bar(13f, 17f, 3f, 4f)
            bar(18f, 4f, 3f, 17f)
        }
    }

    /** Budget — a track with a partial fill, the budget bar itself. */
    val Budget: ImageVector by lazy {
        icon("budget") {
            bar(3f, 6f, 18f, 4f)
            bar(3f, 6f, 11f, 4f)
            bar(3f, 14f, 18f, 4f)
            bar(3f, 14f, 6f, 4f)
        }
    }

    /** The plus on the only FAB in the app. */
    val Plus: ImageVector by lazy {
        icon("plus") {
            bar(11f, 4f, 2f, 16f)
            bar(4f, 11f, 16f, 2f)
        }
    }

    /** Period switcher arrows. */
    val ChevronLeft: ImageVector by lazy {
        icon("chevronLeft") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(15.5f, 4f); lineTo(17f, 5.5f); lineTo(10.5f, 12f)
                lineTo(17f, 18.5f); lineTo(15.5f, 20f); lineTo(7.5f, 12f); close()
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        icon("chevronRight") {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.5f, 4f); lineTo(7f, 5.5f); lineTo(13.5f, 12f)
                lineTo(7f, 18.5f); lineTo(8.5f, 20f); lineTo(16.5f, 12f); close()
            }
        }
    }

    private fun icon(name: String, content: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(content).build()

    /** A rounded rule. Tint is applied at draw time, so the fill is a placeholder. */
    private fun ImageVector.Builder.bar(x: Float, y: Float, w: Float, h: Float) {
        path(fill = SolidColor(Color.Black)) {
            moveTo(x, y)
            lineTo(x + w, y)
            lineTo(x + w, y + h)
            lineTo(x, y + h)
            close()
        }
    }
}
