package com.openminis.app.ui.chat

import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import com.openminis.app.ui.components.MinisBottomSheet
import androidx.compose.ui.unit.DpSize
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors

/**
 * Native Miuix glass sheet for chat actions. The host owns the handle,
 * scrim, gestures and safe height; callers retain their chosen content fraction.
 * Inner-page Back is handled first; normal dismissal keeps content until exit.
 */
@Composable
fun StandardChatSheet(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
    heightFraction: Float = 0.9f,
    onBack: (() -> Boolean)? = null,
    content: @Composable () -> Unit,
) {
    val configuration = LocalConfiguration.current
    val sheetHeight = (configuration.screenHeightDp * heightFraction.coerceIn(0.1f, 1f)).dp

    MinisBottomSheet(
        show = true,
        deferDismissRequest = true,
        onDismissRequest = onDismiss,
        backgroundColor = ChatColors.secondaryBg,
        insideMargin = DpSize.Zero,
    ) {
        val dismissSheet = top.yukonga.miuix.kmp.theme.LocalDismissState.current
        if (onBack != null) {
            // Let an inner page handle Back first; otherwise use the sheet's native exit.
            NavigationBackHandler(
                state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
                isBackEnabled = true,
                onBackCompleted = { if (!onBack()) dismissSheet?.invoke() },
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(sheetHeight),
        ) {
            StandardChatSheetHeader(
                title = title,
                onDismiss = { dismissSheet?.invoke() },
                leadingAction = leadingAction,
            )
            HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator)
            Box(modifier = Modifier.fillMaxSize()) {
                content()
            }
        }
    }
}

/**
 * Shared header row used by all chat sheets — close button on the right,
 * centered title, and an optional leading slot. Reserving a 48.dp slot on the
 * left when [leadingAction] is null keeps the title optically centered.
 */
@Composable
fun StandardChatSheetHeader(
    title: String,
    onDismiss: () -> Unit,
    leadingAction: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leadingAction != null) {
            leadingAction()
        } else {
            Spacer(modifier = Modifier.size(48.dp))
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.headline1,
            fontWeight = FontWeight.SemiBold,
            color = ChatColors.primaryText,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.standard_sheet_close),
                tint = ChatColors.secondaryText,
            )
        }
    }
}
