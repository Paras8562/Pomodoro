package com.example.pomodoro

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Bottom menu: Timer · Presets · Stats. A dot on "Timer" shows that a session is running while you look elsewhere. */
@Composable
fun BottomBar(tab: Int, running: Boolean, compact: Boolean, onTab: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val items: List<Pair<String, ImageVector>> = listOf(
        "Timer" to Icons.Rounded.Timer, "Presets" to Icons.Rounded.Bookmarks, "Stats" to Icons.Rounded.BarChart,
    )
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(28.dp)).background(cs.surfaceContainer)
            .height(if (compact) 56.dp else 66.dp).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, (label, icon) ->
            val sel = tab == i
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).clickable { onTab(i) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    Modifier.height(30.dp).width(58.dp).clip(RoundedCornerShape(15.dp))
                        .background(if (sel) cs.primary.copy(alpha = .20f) else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, label, Modifier.size(22.dp), tint = if (sel) cs.primary else cs.onSurfaceVariant)
                    if (i == 0 && running && tab != 0) {
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 12.dp).size(8.dp).background(cs.primary, CircleShape))
                    }
                }
                Text(
                    label, fontSize = if (compact) 10.sp else 11.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (sel) cs.primary else cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
