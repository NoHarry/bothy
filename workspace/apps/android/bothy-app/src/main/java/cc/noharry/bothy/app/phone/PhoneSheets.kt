package cc.noharry.bothy.app.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.variantChoices
import cc.noharry.bothy.app.R
import cc.noharry.bothy.app.console.ConsoleActions
import cc.noharry.bothy.app.console.ConsoleColors
import cc.noharry.bothy.app.console.ConsoleType
import cc.noharry.bothy.app.console.Dimens
import cc.noharry.bothy.app.console.labelRes
import cc.noharry.bothy.app.console.menuHint
import cc.noharry.bothy.app.console.ui.StatusDot
import cc.noharry.bothy.app.console.variantLabel

/** A phone's choices come up from the bottom, where the thumb is; the tablet's open as menus beside their button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhoneSheet(onDismiss: () -> Unit, title: String?, content: @Composable ColumnScope.() -> Unit) {
    val colors = ConsoleColors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.panel,
        contentColor = colors.text,
        shape = RoundedCornerShape(topStart = SHEET_RADIUS, topEnd = SHEET_RADIUS),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            if (title != null) Text(title, style = ConsoleType.hero, color = colors.text, modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

/** The model, its variant and approval, which the tablet keeps as chips by its composer. Picking a model closes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneModelSheet(state: ServerState, actions: ConsoleActions, onDismiss: () -> Unit) {
    val editor = state.configEditor
    PhoneSheet(onDismiss, stringResource(R.string.console_choose_model)) {
        SheetLabel(stringResource(R.string.console_model))
        val models = editor.modelOptions.ifEmpty { listOf(editor.model).filter { it.isNotBlank() } }
        if (models.isEmpty()) Text(stringResource(R.string.console_no_models), style = ConsoleType.body, color = ConsoleColors.muted)
        models.forEach { model ->
            SheetRow(model, hint = null, selected = model == editor.model) {
                actions.selectModel(model)
                onDismiss()
            }
        }
        if (editor.variantOptions.isNotEmpty()) {
            val (variants, chosen) = editor.variantChoices()
            SheetLabel(stringResource(R.string.console_variant))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                variants.forEach { variant -> SheetChip(variant.variantLabel(), variant == chosen) { actions.selectVariant(variant) } }
            }
        }
        SheetLabel(stringResource(R.string.console_approval))
        ConfirmationMode.entries.forEach { mode ->
            val dot = if (mode.warning) ConsoleColors.warning else null
            SheetRow(stringResource(mode.labelRes()), stringResource(mode.menuHint()), mode == state.confirmationMode, dot) {
                actions.selectConfirmationMode(mode)
            }
        }
    }
}

/** One of the drawer's other features, as a tile of the "more" sheet. */
internal class SheetTile(val icon: Painter, val label: String, val onClick: () -> Unit)

/** The drawer's other features as tiles in a row (plans, A2A, the head unit). */
@Composable
internal fun PhoneMoreSheet(tiles: List<SheetTile>, onDismiss: () -> Unit) {
    val colors = ConsoleColors
    PhoneSheet(onDismiss, title = null) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.forEach { tile ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Dimens.radiusLg))
                        .clickable(role = Role.Button) { onDismiss(); tile.onClick() }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(TILE_ICON_BOX).clip(RoundedCornerShape(TILE_RADIUS)).background(colors.accentSoft), contentAlignment = Alignment.Center) {
                        Icon(tile.icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(28.dp))
                    }
                    Text(tile.label, style = ConsoleType.meta, color = colors.text, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 8.dp))
                }
            }
            // A short row keeps its tiles their size.
            repeat(MORE_TILES_PER_ROW - tiles.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, style = ConsoleType.meta, color = ConsoleColors.muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

/** A choice: its name, what it means, a dot for a warning one, and a check when it is the one in use. */
@Composable
private fun SheetRow(title: String, hint: String?, selected: Boolean, dot: Color? = null, onClick: () -> Unit) {
    val colors = ConsoleColors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(Dimens.radiusLg))
            .background(if (selected) colors.accentSoft else Color.Transparent)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            StatusDot(color = dot, modifier = Modifier.size(8.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = ConsoleType.body, color = colors.text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!hint.isNullOrBlank()) Text(hint, style = ConsoleType.meta, color = colors.muted)
        }
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ConsoleColors
    Text(
        text = label,
        style = ConsoleType.meta,
        color = if (selected) colors.accent else colors.text,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(Dimens.radiusFull))
            .background(if (selected) colors.accentSoft else colors.elevated)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private val SHEET_RADIUS = 28.dp
private val TILE_ICON_BOX = 60.dp
private val TILE_RADIUS = 18.dp
private const val MORE_TILES_PER_ROW = 4
