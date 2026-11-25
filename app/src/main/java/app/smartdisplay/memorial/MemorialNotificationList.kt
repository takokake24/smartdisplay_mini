package app.smartdisplay.memorial

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 収まる件数だけ並べ、残りは「他x件」にまとめる。
 */
@Composable
fun MemorialNotificationList(
    names: List<String>,
    modifier: Modifier = Modifier,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val width = constraints.maxWidth
        val itemConstraints = Constraints(
            minWidth = width,
            maxWidth = width,
            minHeight = 0,
            maxHeight = Constraints.Infinity,
        )
        val measured = names.mapIndexed { index, name ->
            subcompose("name-$index") {
                MemorialLine(name)
            }.first().measure(itemConstraints)
        }
        val moreSample = subcompose("more-sample") {
            MemorialMoreLine("他99件")
        }.first().measure(itemConstraints)
        val maxHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight
        } else {
            Constraints.Infinity
        }
        val visible = visibleMemorialCount(
            heights = measured.map { it.height },
            moreHeight = moreSample.height,
            maxHeight = maxHeight,
        )
        val hidden = names.size - visible
        val more = if (hidden > 0) {
            subcompose("more") {
                MemorialMoreLine("他${hidden}件")
            }.first().measure(itemConstraints)
        } else {
            null
        }
        val contentHeight = measured.take(visible).sumOf { it.height } + (more?.height ?: 0)
        val height = contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var y = 0
            measured.take(visible).forEach { placeable ->
                placeable.place(0, y)
                y += placeable.height
            }
            more?.place(0, y)
        }
    }
}

private fun visibleMemorialCount(
    heights: List<Int>,
    moreHeight: Int,
    maxHeight: Int,
): Int {
    if (heights.isEmpty()) {
        return 0
    }
    if (heights.sum() <= maxHeight) {
        return heights.size
    }
    var count = heights.size - 1
    while (count >= 0) {
        val itemsHeight = heights.take(count).sum()
        if (itemsHeight + moreHeight <= maxHeight) {
            return count
        }
        count--
    }
    return 0
}

@Composable
private fun MemorialLine(name: String) {
    Text(
        text = "・$name",
        color = Color(0xFF222222),
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp),
    )
}

@Composable
private fun MemorialMoreLine(text: String) {
    Text(
        text = text,
        color = Color(0xFF666666),
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
    )
}
