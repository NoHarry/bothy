package cc.noharry.bothy.playground.headunit

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.noharry.bothy.playground.PlaygroundStore
import cc.noharry.bothy.playground.home.World
import cc.noharry.bothy.playground.cabin.CabinScene
import cc.noharry.bothy.playground.cabin.Seat
import cc.noharry.bothy.playground.cabin.climateLine

/** A phone held upright. Wide head-unit columns stay for landscape and tablets. */
internal fun usesPortraitHeadUnit(widthDp: Int, orientation: Int): Boolean =
    widthDp < 600 && orientation == Configuration.ORIENTATION_PORTRAIT

@Composable
internal fun PortraitHeadUnit(world: World, flash: Flash, store: PlaygroundStore) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val nav = world.cabin.nav
    Column(Modifier.fillMaxSize().background(PlaygroundColors.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
            when (tab) {
                1 -> CabinScene(world.cabin, flash, Modifier.fillMaxSize(), compact = true, speaking = false)
                2 -> Text(world.cabin.climateLine(), color = PlaygroundColors.text, fontSize = 22.sp, modifier = Modifier.padding(12.dp))
                else -> Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nav.destination?.name ?: stringResource(cc.noharry.bothy.playground.R.string.playground_scene_cabin), color = PlaygroundColors.text, fontSize = 22.sp)
                    Text(nav.currentRoad, color = PlaygroundColors.muted, fontSize = 14.sp)
                    if (nav.totalSeconds > 0) {
                        Text("${nav.remainingSeconds / 60} min · ${nav.remainingMeters / 1000} km", color = PlaygroundColors.sage, fontSize = 16.sp)
                    }
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(PlaygroundColors.panel).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2).forEach { index ->
                    val label = when (index) {
                        1 -> "座舱"
                        2 -> "空调"
                        else -> "路线"
                    }
                    Text(
                        label,
                        color = if (tab == index) PlaygroundColors.sage else PlaygroundColors.muted,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(999.dp)).background(if (tab == index) PlaygroundColors.sage.copy(alpha = 0.16f) else PlaygroundColors.bg).clickable { tab = index }.padding(vertical = 8.dp),
                    )
                }
            }
            if (tab == 1) {
                Seat.entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { seat ->
                            Text(seat.name, color = PlaygroundColors.text, modifier = Modifier.weight(1f).height(72.dp).clip(RoundedCornerShape(16.dp)).background(PlaygroundColors.bg).padding(12.dp))
                        }
                    }
                }
            }
        }
    }
}
