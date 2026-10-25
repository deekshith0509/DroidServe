package com.deekshith.droidserve.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.unit.dp

/**
 * Hand-authored line/solid icons as [ImageVector]s on a 24×24 grid. Dependency-free (no
 * material-icons-extended, which would bloat the deliberately-tiny TV app) and rendered via
 * androidx Icon, which tints them to the current state colour. Replaces every emoji in the UI.
 */
private fun stroke(name: String, w: Float = 1.9f, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = PathBuilder().apply(block).nodes,
            stroke = SolidColor(Color.Black), strokeLineWidth = w,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        )
        .build()

private fun solid(name: String, block: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = PathBuilder().apply(block).nodes, fill = SolidColor(Color.Black))
        .build()

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
    close()
}

object DsIcons {
    val Folder = stroke("folder") {
        moveTo(3f, 7f); lineTo(9.5f, 7f); lineTo(11.5f, 9.3f); lineTo(21f, 9.3f)
        lineTo(21f, 18f); lineTo(3f, 18f); close()
    }
    val Video = stroke("video") {
        moveTo(3.5f, 6f); lineTo(20.5f, 6f); lineTo(20.5f, 18f); lineTo(3.5f, 18f); close()
        moveTo(10f, 9.3f); lineTo(15.5f, 12f); lineTo(10f, 14.7f); close()
    }
    val Audio = stroke("audio") {
        moveTo(9f, 15.5f); lineTo(9f, 6f); lineTo(18f, 4f); lineTo(18f, 13.5f)
        circle(7f, 15.7f, 2f)
        circle(16f, 13.7f, 2f)
    }
    val Image = stroke("image") {
        moveTo(3.5f, 5.5f); lineTo(20.5f, 5.5f); lineTo(20.5f, 18.5f); lineTo(3.5f, 18.5f); close()
        circle(8f, 9.5f, 1.6f)
        moveTo(5f, 17f); lineTo(10f, 12f); lineTo(13f, 15f); lineTo(16f, 11.5f); lineTo(20.5f, 16.5f)
    }
    val Apk = stroke("apk") {
        moveTo(7f, 12f); arcToRelative(5f, 5f, 0f, false, true, 10f, 0f); lineTo(7f, 12f); close()
        moveTo(9f, 7.6f); lineTo(8f, 5.6f)
        moveTo(15f, 7.6f); lineTo(16f, 5.6f)
        moveTo(7f, 13.5f); lineTo(17f, 13.5f); lineTo(17f, 18f); lineTo(7f, 18f); close()
    }
    val Doc = stroke("doc") {
        moveTo(6f, 4f); lineTo(14f, 4f); lineTo(18f, 8f); lineTo(18f, 20f); lineTo(6f, 20f); close()
        moveTo(14f, 4f); lineTo(14f, 8f); lineTo(18f, 8f)
        moveTo(8.5f, 12f); lineTo(15.5f, 12f)
        moveTo(8.5f, 15f); lineTo(15.5f, 15f)
        moveTo(8.5f, 9f); lineTo(11.5f, 9f)
    }
    val Search = stroke("search") {
        circle(10.5f, 10.5f, 5.5f)
        moveTo(14.6f, 14.6f); lineTo(19f, 19f)
    }
    val Grid = stroke("grid") {
        moveTo(4f, 4f); lineTo(10f, 4f); lineTo(10f, 10f); lineTo(4f, 10f); close()
        moveTo(14f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(14f, 10f); close()
        moveTo(4f, 14f); lineTo(10f, 14f); lineTo(10f, 20f); lineTo(4f, 20f); close()
        moveTo(14f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(14f, 20f); close()
    }
    val ListView = stroke("list") {
        moveTo(4f, 6.5f); lineTo(20f, 6.5f)
        moveTo(4f, 12f); lineTo(20f, 12f)
        moveTo(4f, 17.5f); lineTo(20f, 17.5f)
    }
    val Link = stroke("link") {
        moveTo(13f, 5f); lineTo(5f, 5f); lineTo(5f, 19f); lineTo(19f, 19f); lineTo(19f, 11f)
        moveTo(11.5f, 12.5f); lineTo(19f, 5f)
        moveTo(14.5f, 5f); lineTo(19f, 5f); lineTo(19f, 9.5f)
    }
    val Magnet = stroke("magnet") {
        moveTo(6f, 5f); lineTo(6f, 13f); arcToRelative(6f, 6f, 0f, false, false, 12f, 0f); lineTo(18f, 5f)
        moveTo(6f, 9f); lineTo(9.5f, 9f)
        moveTo(18f, 9f); lineTo(14.5f, 9f)
    }
    val Play = solid("play") { moveTo(7f, 5f); lineTo(19f, 12f); lineTo(7f, 19f); close() }
    val Pause = solid("pause") {
        moveTo(7f, 5f); lineTo(10.5f, 5f); lineTo(10.5f, 19f); lineTo(7f, 19f); close()
        moveTo(13.5f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(13.5f, 19f); close()
    }
    val Prev = solid("prev") {
        moveTo(7f, 5f); lineTo(9f, 5f); lineTo(9f, 19f); lineTo(7f, 19f); close()
        moveTo(19f, 5f); lineTo(19f, 19f); lineTo(10f, 12f); close()
    }
    val Next = solid("next") {
        moveTo(5f, 5f); lineTo(14f, 12f); lineTo(5f, 19f); close()
        moveTo(15f, 5f); lineTo(17f, 5f); lineTo(17f, 19f); lineTo(15f, 19f); close()
    }
    val Device = stroke("device") {
        moveTo(8f, 3.5f); lineTo(16f, 3.5f); lineTo(16f, 20.5f); lineTo(8f, 20.5f); close()
        moveTo(10.5f, 5.5f); lineTo(13.5f, 5.5f)
        moveTo(10.5f, 18.3f); lineTo(13.5f, 18.3f)
    }
    val Lock = stroke("lock") {
        moveTo(5.5f, 10.5f); lineTo(18.5f, 10.5f); lineTo(18.5f, 20f); lineTo(5.5f, 20f); close()
        moveTo(8f, 10.5f); lineTo(8f, 8f); arcToRelative(4f, 4f, 0f, false, true, 8f, 0f); lineTo(16f, 10.5f)
        circle(12f, 14.6f, 1.2f)
    }
    val Info = stroke("info") {
        circle(12f, 12f, 8.5f)
        moveTo(12f, 11f); lineTo(12f, 16.5f)
        moveTo(12f, 8f); lineTo(12f, 8.1f)
    }
    val Warning = stroke("warning") {
        moveTo(12f, 4f); lineTo(21f, 19.5f); lineTo(3f, 19.5f); close()
        moveTo(12f, 10f); lineTo(12f, 14.5f)
        moveTo(12f, 17f); lineTo(12f, 17.1f)
    }
    val Refresh = stroke("refresh") {
        moveTo(19.5f, 12f)
        arcToRelative(7.5f, 7.5f, 0f, true, true, -2.3f, -5.4f)
        moveTo(19.6f, 5f); lineTo(17.2f, 6.6f); lineTo(14.9f, 5.2f)
    }
    val Chevron = stroke("chevron", w = 2.1f) {
        moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f)
    }
    val Keyboard = stroke("keyboard") {
        moveTo(3.5f, 7f); lineTo(20.5f, 7f); lineTo(20.5f, 17f); lineTo(3.5f, 17f); close()
        moveTo(7f, 10.5f); lineTo(7f, 10.6f)
        moveTo(11f, 10.5f); lineTo(11f, 10.6f)
        moveTo(15f, 10.5f); lineTo(15f, 10.6f)
        moveTo(8f, 14f); lineTo(16f, 14f)
    }
    val Back = stroke("back") {
        moveTo(11f, 6f); lineTo(5f, 12f); lineTo(11f, 18f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    }
}
