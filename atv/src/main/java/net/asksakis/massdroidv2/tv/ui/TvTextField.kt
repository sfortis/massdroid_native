package net.asksakis.massdroidv2.tv.ui

import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusProperties
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * A single-line text field for the TV: a framed box that shows focus, with D-pad UP and DOWN
 * always moving focus out of it and the keyboard's action wired to [onImeAction].
 *
 * Moving onto the field focuses only its frame. The keyboard opens when the user presses OK or
 * clicks it, the way the platform's own TV forms work; before, the keyboard opened on every
 * pass through a field on the way to the button below it. [focusRequester] focuses the frame.
 */
@Composable
internal fun TvTextField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    imeAction: ImeAction,
    onImeAction: () -> Unit,
    masked: Boolean = false,
    focusRequester: FocusRequester? = null,
    onValueChange: (String) -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val frameFocus = focusRequester ?: remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val startEditing = { editing = true }
    // The field takes the focus only once editing starts, and the keyboard is shown with it.
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }
    val stopEditing = {
        editing = false
        runCatching { frameFocus.requestFocus() }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(frameFocus)
                .onFocusChanged { if (!editing) focused = it.isFocused }
                // OK on the remote, or a click, opens the keyboard.
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    startEditing()
                }
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                .border(
                    width = if (focused) 2.dp else 1.dp,
                    color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    shape = MaterialTheme.shapes.small
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 20.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = false
                ),
                keyboardActions = KeyboardActions(onAny = {
                    // Back to the frame first, so an action that moves the focus on starts from
                    // the field's place, and the keyboard closes with the editing.
                    stopEditing()
                    onImeAction()
                }),
                visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus)
                    // Not reachable with the D-pad: only OK or a click on the frame opens it.
                    .focusProperties { canFocus = editing }
                    .onFocusChanged {
                        if (it.isFocused) {
                            focused = true
                        } else {
                            // Leaving the field (UP, DOWN, a tap elsewhere) ends the editing; a
                            // return to the frame marks it focused again on its own.
                            focused = false
                            editing = false
                        }
                    }
                    // Compose moves focus out of a text field with UP and DOWN only when the
                    // remote reports itself as a D-pad. Many TV remotes (HDMI-CEC, several
                    // Bluetooth ones) send their arrows as plain keyboard keys, and the field
                    // then keeps UP and DOWN as cursor moves, so the cursor could never leave
                    // the first field (#79). A single-line field has no use for them, so they
                    // always move focus here. LEFT and RIGHT still move the text cursor.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            // BACK with the keyboard already closed ends the editing and leaves
                            // the cursor on the frame, instead of leaving the screen.
                            Key.Back -> {
                                stopEditing()
                                true
                            }
                            else -> false
                        }
                    }
            )
        }
    }
}
