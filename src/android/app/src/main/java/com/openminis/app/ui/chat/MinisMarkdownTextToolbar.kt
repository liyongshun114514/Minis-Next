package com.openminis.app.ui.chat

import top.yukonga.miuix.kmp.theme.MiuixTheme
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.openminis.app.R

/**
 * Replaces the system Copy/Cut/Paste/SelectAll toolbar that a
 * [androidx.compose.foundation.text.selection.SelectionContainer] normally
 * invokes. Surfaces three actions, mirroring iOS `SelectableMarkdownTextView`:
 *
 *   - **Copy** — selected substring (delegates to `onCopyRequested`).
 *   - **Copy Markdown** — full raw markdown of the message that owns the
 *     selection. Resolved via the [MessageBoundsRegistry] lookup with the
 *     selection rect; if no message matches (cross-message selection or
 *     out-of-bounds rect) the action is hidden so we never copy the wrong
 *     content.
 *   - **Copy Rich Text** — same lookup, exported as HTML+plain dual ClipData.
 *
 * The toolbar is driven by a [mutableStateOf]-backed state holder; pair it
 * with [MinisMarkdownTextToolbarHost] which renders the floating popup. The
 * split exists because [TextToolbar] is a non-composable platform interface
 * but our UI must live inside the composition.
 */
internal class MinisMarkdownTextToolbar(
    private val context: Context,
    private val registry: MessageBoundsRegistry,
    /**
     * Invoked when the user taps **Add to Chat Input**. Receives the
     * currently-selected substring (resolved via a clipboard round-trip
     * through the Compose-supplied [onCopyRequested] callback — see
     * [addSelectionToInput]); the host is expected to append it to the
     * chat composer's input state. Null disables the action.
     */
    private val onAddToInput: ((String) -> Unit)? = null,
    /**
     * [T-android-selection-readaloud] Invoked when the user taps **Read Aloud**.
     * Receives the currently-selected substring (same clipboard round-trip as
     * [addSelectionToInput]); the host speaks it through ReadAloudPlayer. Null
     * disables the action. The callback owns the player's lifecycle — this
     * toolbar deliberately does not create one it could never dispose.
     */
    private val onReadAloud: ((String) -> Unit)? = null,
    /**
     * [T-android-readaloud-selection-vs-reply] Invoked when the user taps
     * **Read from Start**. Receives the owning message's markdown source (not
     * the selection); the host speaks it through the same reader. Null hides
     * the action — the caller passes null while the reply is still streaming,
     * matching iOS.
     */
    private val onReadFromStart: ((String) -> Unit)? = null,
    /**
     * [T-android-readaloud-selection-vs-reply] Whether a reply is streaming
     * right now. A lambda, not a Boolean: this toolbar is constructed once
     * inside `remember`, so a captured value would freeze at construction time
     * and the gate would answer for the wrong moment.
     */
    private val isStreamingNow: () -> Boolean = { false },
    /**
     * [T-android-markdown-table-copy-actions] The MinisTextKit selection
     * controller. This toolbar is the one Compose's SelectionContainer shows
     * (via LocalTextToolbar), and a table-cell long-press also sets the
     * MinisTextKit selection, so we read the table actions for the selected
     * message off the controller and append "Copy Table" / "Copy Table Image"
     * to THIS bar — keeping all actions in one row instead of a separate popup.
     */
    internal val selectionController: SelectionController? = null,
    /**
     * [T-multi-select-share] Opens the share picker with the round of the selected
     * message preselected (null when it cannot be told which message). Null hides
     * the action; shown last, as on iOS.
     */
    private val onShareChat: ((String?) -> Unit)? = null,
    /**
     * [T-in-chat-search] "Search in Chat" with the selection as the query
     * (iOS: the assistant text selection menu). Null hides the action.
     */
    private val onSearchInChat: ((String) -> Unit)? = null,
) : TextToolbar {

    internal var state by mutableStateOf(ToolbarState())
        private set

    internal val canAddToInput: Boolean get() = onAddToInput != null

    internal val canShareChat: Boolean get() = onShareChat != null

    internal fun shareChat() {
        val sink = onShareChat ?: return
        sink(
            selectionController?.singleMessageId()
                ?: selectionController?.selection?.value?.start?.shard?.messageId,
        )
    }

    override val status: TextToolbarStatus
        get() = if (state.visible) TextToolbarStatus.Shown else TextToolbarStatus.Hidden

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?,
    ) {
        state = ToolbarState(
            visible = true,
            rect = rect,
            onCopyRequested = onCopyRequested,
            originatingMarkdown = registry.markdownAt(rect),
            lastShownAtMs = android.os.SystemClock.uptimeMillis(),
        )
    }

    override fun hide() {
        state = state.copy(visible = false)
    }

    /**
     * Extract the selected substring and forward to [onAddToInput] so the host
     * can append it to the chat input. See [withSelection] for how the
     * selection is resolved. No-op when [onAddToInput] isn't wired.
     */
    internal fun addSelectionToInput() {
        val sink = onAddToInput ?: return
        withSelection(sink)
    }

    /**
     * [T-android-selection-readaloud] Speak just the selected substring (not the
     * whole message) through the host-supplied reader.
     */
    internal fun readAloudSelection() {
        val sink = onReadAloud ?: return
        withSelection(sink)
    }

    internal val canReadAloud: Boolean get() = onReadAloud != null

    internal val canSearchInChat: Boolean get() = onSearchInChat != null

    internal fun searchSelectionInChat() {
        val sink = onSearchInChat ?: return
        withSelection(sink)
    }

    /**
     * [T-android-readaloud-selection-vs-reply] Speak the WHOLE message that
     * owns the current selection, from its start — iOS's "Read from Start".
     *
     * Unlike [readAloudSelection] this needs no clipboard round-trip: the
     * message's markdown source is already resolved into
     * [ToolbarState.originatingMarkdown] when the bar is shown, and that is
     * exactly the text to read. It is also why the action can only be offered
     * when that field is non-null — a selection whose owning message we cannot
     * identify has no "whole reply" to restart.
     */
    internal fun readFromStart() {
        val sink = onReadFromStart ?: return
        val source = state.originatingMarkdown ?: return
        if (source.isNotBlank()) sink(source)
    }

    internal val canReadFromStart: Boolean
        get() = onReadFromStart != null &&
            state.originatingMarkdown != null &&
            // Suppressed mid-stream, matching iOS: restarting a reply that is
            // still arriving would narrate a text that keeps growing under the
            // narration. Read Selection stays available — a range the user
            // could select already exists.
            !isStreamingNow()

    /**
     * Resolve the current selection and hand it to [sink].
     *
     * Compose's [TextToolbar] interface only exposes the selection indirectly
     * via the supplied `onCopyRequested` callback — there is no public "give me
     * the AnnotatedString selection" API in `foundation` 1.7. We round-trip
     * through the system clipboard:
     *
     *   1. Snapshot the user's current primary clip (so we can restore it — we
     *      don't want a feature that silently overwrites what they had copied).
     *   2. Invoke `onCopyRequested` — Compose writes the selected substring to
     *      the clipboard.
     *   3. Read that text back as the selection.
     *   4. Restore the original clip.
     *   5. Forward the selection to [sink].
     *
     * Shared by [addSelectionToInput] and [readAloudSelection] so both actions
     * use the identical restore-the-user's-clipboard discipline. If anything
     * goes wrong (`onCopyRequested` absent, blank selection) this is a no-op —
     * it never throws and never leaves the clipboard in an unrelated state.
     */
    private fun withSelection(sink: (String) -> Unit) {
        val onCopy = state.onCopyRequested ?: return
        val cm = clipboard()
        val original = runCatching { cm.primaryClip }.getOrNull()
        try {
            onCopy.invoke()
            val selection = runCatching {
                cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()?.trim().orEmpty()
            if (selection.isNotEmpty()) sink(selection)
        } finally {
            // Restore the user's prior clipboard so the action stays
            // additive — only the chat input / speech changes, the clipboard
            // is exactly as the user left it.
            if (original != null) runCatching { cm.setPrimaryClip(original) }
        }
    }

    private fun clipboard(): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    internal fun copyMarkdown() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyMarkdown(context, md)
        toast(R.string.selection_copied_as_markdown_toast)
    }

    internal fun copyRichText() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyRichText(context, md)
        toast(R.string.selection_copied_as_rich_text_toast)
    }

    /**
     * [T-android-copy-full-plain-text] Copy the message as RENDERED plain
     * text: markdown markers resolved to what the user sees, but table cells
     * and math expressions still expanded — dropping those would lose content
     * rather than formatting. Mirrors iOS `1b4353367`.
     */
    internal fun copyPlainText() {
        val md = state.originatingMarkdown ?: return
        MarkdownClipboard.copyPlain(context, md)
        toast(R.string.selection_copied_as_plain_text_toast)
    }

    private fun toast(resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    internal data class ToolbarState(
        val visible: Boolean = false,
        val rect: Rect = Rect.Zero,
        val onCopyRequested: (() -> Unit)? = null,
        /** Joined markdown of the message that owns [rect], or null if cross-message. */
        val originatingMarkdown: String? = null,
        /**
         * `SystemClock.uptimeMillis()` of the most recent [showMenu] call, or
         * 0 if the toolbar has never been shown. Updated only on `showMenu` —
         * [hide] preserves the previous value so consumers can distinguish a
         * mid-drag hide (recent timestamp) from a stale post-clear state
         * (timestamp many seconds old). Used by ChatScreen's selection
         * auto-scroll loop to bail when no fresh selection activity has been
         * observed for a long time, regardless of the sticky `selectionActive`
         * debounce.
         */
        val lastShownAtMs: Long = 0L,
    )
}

/**
 * Renders the floating toolbar controlled by [MinisMarkdownTextToolbar]. Place
 * exactly one instance under the same `CompositionLocalProvider` that supplies
 * the toolbar so it draws above the selected text.
 */
@Composable
internal fun MinisMarkdownTextToolbarHost(toolbar: MinisMarkdownTextToolbar) {
    val state = toolbar.state
    if (!state.visible || toolbar.selectionController?.selection?.value != null) return
    val context = androidx.compose.ui.platform.LocalContext.current
    fun label(id: Int) = context.getString(id)
    val items = buildList {
        state.onCopyRequested?.let { copy -> add(SystemSelectionAction(label(R.string.selection_copy), onClick = copy)) }
        if (toolbar.canAddToInput) add(SystemSelectionAction(label(R.string.selection_add_to_chat_input), onClick = toolbar::addSelectionToInput))
        if (toolbar.canSearchInChat) add(SystemSelectionAction(label(R.string.chat_search_in_chat), onClick = toolbar::searchSelectionInChat))
        if (toolbar.canReadAloud) add(SystemSelectionAction(label(R.string.selection_read_aloud), onClick = toolbar::readAloudSelection))
        if (toolbar.canReadFromStart) add(SystemSelectionAction(label(R.string.selection_read_from_start), onClick = toolbar::readFromStart))
        if (state.originatingMarkdown != null) {
            add(SystemSelectionAction(label(R.string.selection_copy_full_markdown), onClick = toolbar::copyMarkdown))
            add(SystemSelectionAction(label(R.string.selection_copy_full_rich_text), onClick = toolbar::copyRichText))
            add(SystemSelectionAction(label(R.string.selection_copy_full_plain_text), onClick = toolbar::copyPlainText))
        }
        toolbar.selectionController?.selectionTableActions()?.let { table ->
            add(SystemSelectionAction(label(R.string.markdown_table_copy_table), onClick = table.copyTableMarkdown))
            add(SystemSelectionAction(label(R.string.markdown_table_copy_table_image), onClick = table.copyTableImage))
        }
        if (toolbar.canShareChat) add(SystemSelectionAction(label(R.string.chat_share_share_chat), onClick = toolbar::shareChat))
    }
    SystemSelectionMenu(state.rect, items, onDismiss = toolbar::hide)
}
