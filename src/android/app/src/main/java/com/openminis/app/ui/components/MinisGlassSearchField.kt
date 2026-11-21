package com.openminis.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.GlassTextField
import top.yukonga.miuix.kmp.glass.GlassTextFieldDefaults
import top.yukonga.miuix.kmp.glass.glassSurface
import top.yukonga.miuix.kmp.material.GlassShape
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported

/** Search chrome samples a sibling body; input, focus and results remain caller-owned. */
@Composable
internal fun MinisGlassSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    label: String = "",
    useLabelAsPlaceholder: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val root = LocalView.current.rootView
    val sameWindow = LocalMinisPageWindow.current === root || LocalMinisOverlayWindow.current === root
    val backdrop = LocalMinisActionBackdrop.current.takeIf { sameWindow && isRuntimeShaderSupported() }
    val shape = GlassShape(24.dp)
    val surface = glassSurface(backdrop, GlassPopupDefaults.visuals())
    Box(modifier.then(surface(shape))) {
        GlassTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            label = label,
            useLabelAsPlaceholder = useLabelAsPlaceholder,
            cornerRadius = 24.dp,
            colors = GlassTextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
            leadingIcon = leadingIcon?.let { icon -> {
                Box(Modifier.padding(start = 16.dp, end = 10.dp)) { icon() }
            } },
            trailingIcon = trailingIcon,
        )
    }
}
