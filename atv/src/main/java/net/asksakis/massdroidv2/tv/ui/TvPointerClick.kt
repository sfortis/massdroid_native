package net.asksakis.massdroidv2.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.launch

/** The same zoom tv-material gives a focused card or button. */
const val POINTER_PRESS_SCALE = 1.1f

/** For components that keep their focus zoom at 1 so they stay inside their container. */
const val POINTER_PRESS_SCALE_SUBTLE = 1.02f

private const val PRESS_GROW_MS = 90
private const val PRESS_SETTLE_MS = 160

/**
 * Lets a mouse or touch click reach a tv-material clickable component.
 *
 * In tv-material 1.0.0, Button, IconButton, Card and the clickable Surface react only to the
 * OK key. They have no pointer input, so a click with a mouse or a touch screen does nothing
 * (#79). Add this to the component's modifier with the same action as its onClick.
 *
 * The click runs the action at once and briefly zooms the component to [pressScale] and back,
 * so the click is visible. It does not move focus: the remote's cursor stays where it was.
 *
 * Remove it once tv-material handles pointer clicks itself, or each click will run twice.
 */
fun Modifier.pointerClick(
    enabled: Boolean = true,
    pressScale: Float = POINTER_PRESS_SCALE,
    onClick: () -> Unit
): Modifier = this then PointerClickElement(enabled, pressScale, onClick)

private data class PointerClickElement(
    val enabled: Boolean,
    val pressScale: Float,
    val onClick: () -> Unit
) : ModifierNodeElement<PointerClickNode>() {
    override fun create() = PointerClickNode(enabled, pressScale, onClick)

    override fun update(node: PointerClickNode) {
        node.enabled = enabled
        node.pressScale = pressScale
        node.onClick = onClick
    }
}

private class PointerClickNode(
    var enabled: Boolean,
    var pressScale: Float,
    var onClick: () -> Unit
) : DelegatingNode(), LayoutModifierNode {
    private val scale = Animatable(1f)

    init {
        // The handler reads the current fields, so a recomposition with a new lambda does not
        // restart the gesture detector.
        delegate(SuspendingPointerInputModifierNode {
            detectTapGestures(onTap = {
                if (enabled) {
                    onClick()
                    pulse()
                }
            })
        })
    }

    // A new click cancels a running pulse and starts again from the current scale. The action
    // has already run by then, so a quick second click is never lost.
    private fun pulse() {
        coroutineScope.launch {
            scale.animateTo(pressScale, tween(PRESS_GROW_MS))
            scale.animateTo(1f, tween(PRESS_SETTLE_MS))
        }
    }

    // The scale is read only inside the layer block, so the pulse redraws the layer without
    // recomposing or measuring the component again.
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                scaleX = scale.value
                scaleY = scale.value
            }
        }
    }
}
