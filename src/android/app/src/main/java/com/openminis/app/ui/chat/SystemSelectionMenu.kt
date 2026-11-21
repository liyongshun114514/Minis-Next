package com.openminis.app.ui.chat

import android.graphics.Rect as AndroidRect
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView

internal class SystemSelectionAction(
    val label: String,
    val children: List<SystemSelectionAction> = emptyList(),
    val onClick: () -> Unit = {},
)

/** Native floating ActionMode owns positioning, overflow, theme and accessibility. */
@Composable
internal fun SystemSelectionMenu(
    rect: Rect,
    actions: List<SystemSelectionAction>,
    onDismiss: () -> Unit,
) {
    val view = LocalView.current
    val currentActions = rememberUpdatedState(actions)
    val dismiss = rememberUpdatedState(onDismiss)
    val anchor = rememberUpdatedState(rect)
    val holder = remember(view) { arrayOfNulls<ActionMode>(1) }
    fun labels(items: List<SystemSelectionAction>): List<String> = items.flatMap { listOf(it.label) + labels(it.children) }
    val menuLabels = labels(actions)
    DisposableEffect(view) {
        var disposing = false
        val callback = object : ActionMode.Callback2() {
            val callbacks = mutableMapOf<Int, List<Int>>()
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = true
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.clear()
                callbacks.clear()
                var id = 1
                fun addItems(target: Menu, items: List<SystemSelectionAction>, parent: List<Int> = emptyList()) {
                    for ((index, action) in items.withIndex()) {
                        val path = parent + index
                        val itemId = id++
                        if (action.children.isEmpty()) {
                            callbacks[itemId] = path
                            target.add(Menu.NONE, itemId, Menu.NONE, action.label)
                                .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                        } else {
                            val sub = target.addSubMenu(Menu.NONE, itemId, Menu.NONE, action.label)
                            sub.item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                            addItems(sub, action.children, path)
                        }
                    }
                }
                addItems(menu, currentActions.value)
                return true
            }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                val path = callbacks[item.itemId] ?: return false
                var items = currentActions.value
                var selected: SystemSelectionAction? = null
                for (index in path) {
                    selected = items.getOrNull(index) ?: return false
                    items = selected.children
                }
                selected?.onClick?.invoke()
                mode.finish()
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) {
                holder[0] = null
                if (!disposing) dismiss.value()
            }
            override fun onGetContentRect(mode: ActionMode, target: View, outRect: AndroidRect) {
                val location = IntArray(2)
                target.getLocationInWindow(location)
                val r = anchor.value
                outRect.set(r.left.toInt() - location[0], r.top.toInt() - location[1],
                    r.right.toInt() - location[0], r.bottom.toInt() - location[1])
            }
        }
        holder[0] = view.startActionMode(callback, ActionMode.TYPE_FLOATING)
        holder[0]?.invalidate()
        onDispose {
            // Disposal after selection clearing must not recursively dismiss a new mode.
            disposing = true
            val mode = holder[0]
            holder[0] = null
            mode?.finish()
        }
    }
    DisposableEffect(view, menuLabels) {
        holder[0]?.invalidate()
        onDispose { }
    }
    SideEffect { holder[0]?.invalidateContentRect() }
}
