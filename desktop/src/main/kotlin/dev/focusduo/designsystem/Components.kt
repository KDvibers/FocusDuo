package dev.focusduo.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FocusDuoLogo(modifier: Modifier = Modifier, compact: Boolean = false) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(34.dp, 27.dp).semantics { contentDescription = "Логотип FocusDuo" }) {
            drawCircle(Color(0xFFB0C7A7), radius = size.height / 2, center = Offset(size.height / 2, size.height / 2))
            drawCircle(Color(0xFF1C543D).copy(alpha = 0.86f), radius = size.height / 2, center = Offset(size.width - size.height / 2, size.height / 2))
        }
        Text("FocusDuo", fontSize = if (compact) 18.sp else 22.sp, fontWeight = FontWeight.Bold, color = FocusDuoColors.Text)
    }
}

@Composable
fun FocusCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, color = FocusDuoColors.Paper, shape = MaterialTheme.shapes.medium, border = BorderStroke(1.dp, FocusDuoColors.Border), elevation = 0.dp, content = content)
}

@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, modifier = modifier.defaultMinSize(minHeight = 48.dp), enabled = enabled,
        shape = MaterialTheme.shapes.small, elevation = ButtonDefaults.elevation(0.dp, 0.dp, 0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 12.dp), content = content)
}

@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.defaultMinSize(minHeight = 44.dp), enabled = enabled,
        shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, FocusDuoColors.Border),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = FocusDuoColors.Text),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 10.dp), content = content)
}

@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    Surface(modifier, color = FocusDuoColors.PaleGreen, shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Canvas(Modifier.size(6.dp)) { drawCircle(FocusDuoColors.Green) }
            Text("Демо", style = MaterialTheme.typography.caption, fontWeight = FontWeight.SemiBold, color = FocusDuoColors.Green)
        }
    }
}

enum class FocusIcon { Home, Check, Arrow, Leaf, People, History, Settings }

/** Small consistent outline icons, drawn as Compose primitives rather than platform glyphs. */
@Composable
fun FocusIcon(icon: FocusIcon, modifier: Modifier = Modifier, color: Color = FocusDuoColors.Green) {
    Canvas(modifier.size(22.dp)) {
        val scale = size.width / 24f
        fun point(x: Float, y: Float) = Offset(x * scale, y * scale)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, point(x1, y1), point(x2, y2), strokeWidth = 1.8f * scale, cap = StrokeCap.Round)
        when (icon) {
            FocusIcon.Home -> {
                val path = Path().apply {
                    moveTo(3 * scale, 10 * scale); lineTo(12 * scale, 3 * scale); lineTo(21 * scale, 10 * scale)
                    lineTo(21 * scale, 21 * scale); lineTo(15 * scale, 21 * scale); lineTo(15 * scale, 14 * scale)
                    lineTo(9 * scale, 14 * scale); lineTo(9 * scale, 21 * scale); lineTo(3 * scale, 21 * scale); close()
                }
                drawPath(path, color, style = Stroke(width = 1.7f * scale, cap = StrokeCap.Round))
            }
            FocusIcon.Check -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 7f) }
            FocusIcon.History -> {
                drawCircle(color, 9f * scale, point(12f, 12f), style = Stroke(1.7f * scale))
                line(12f, 6f, 12f, 12f); line(12f, 12f, 16f, 14f)
            }
            FocusIcon.Settings -> {
                drawCircle(color, 7f * scale, point(12f, 12f), style = Stroke(1.7f * scale))
                drawCircle(color, 2.6f * scale, point(12f, 12f), style = Stroke(1.7f * scale))
                for (i in 0..7) {
                    val angle = i * Math.PI / 4
                    val x = kotlin.math.cos(angle).toFloat()
                    val y = kotlin.math.sin(angle).toFloat()
                    line(12f + 7f * x, 12f + 7f * y, 12f + 10f * x, 12f + 10f * y)
                }
            }
            FocusIcon.Arrow -> { line(4f, 12f, 20f, 12f); line(14f, 6f, 20f, 12f); line(14f, 18f, 20f, 12f) }
            FocusIcon.Leaf -> {
                val path = Path().apply {
                    moveTo(5 * scale, 19 * scale)
                    cubicTo(2 * scale, 8 * scale, 10 * scale, 3 * scale, 21 * scale, 3 * scale)
                    cubicTo(21 * scale, 13 * scale, 17 * scale, 22 * scale, 5 * scale, 19 * scale)
                    close()
                }
                drawPath(path, color, style = Stroke(1.7f * scale))
                line(4f, 21f, 15f, 10f)
            }
            FocusIcon.People -> {
                drawCircle(color, 3f * scale, point(9f, 7f), style = Stroke(1.7f * scale))
                drawCircle(color, 2.4f * scale, point(17.5f, 8f), style = Stroke(1.7f * scale))
                val first = Path().apply {
                    moveTo(2f * scale, 21f * scale)
                    lineTo(2f * scale, 18f * scale)
                    cubicTo(2f * scale, 12f * scale, 16f * scale, 12f * scale, 16f * scale, 18f * scale)
                    lineTo(16f * scale, 21f * scale)
                    close()
                }
                drawPath(first, color, style = Stroke(1.7f * scale, cap = StrokeCap.Round))
                val second = Path().apply {
                    moveTo(18f * scale, 14f * scale)
                    cubicTo(23f * scale, 14f * scale, 22f * scale, 18f * scale, 22f * scale, 21f * scale)
                    lineTo(19f * scale, 21f * scale)
                }
                drawPath(second, color, style = Stroke(1.7f * scale, cap = StrokeCap.Round))
            }
        }
    }
}
