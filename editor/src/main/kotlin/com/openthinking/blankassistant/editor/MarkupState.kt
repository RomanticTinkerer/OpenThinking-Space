package com.openthinking.blankassistant.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import com.dot.gallery.feature_node.domain.model.editor.DrawMode
import com.dot.gallery.feature_node.domain.model.editor.DrawType
import com.dot.gallery.feature_node.domain.model.editor.MarkupBrush
import com.dot.gallery.feature_node.domain.model.editor.PathProperties
import com.dot.gallery.feature_node.domain.model.editor.TextAnnotation

/**
 * The markup slice of ReFra's EditViewModel (paths / undo / redo / draw mode / draw type presets),
 * as a plain Compose state holder. Lists are replaced (never mutated) so the painter recomposes.
 * Preset values in [changeDrawType] are copied from ReFra.
 */
internal class MarkupState {
    var paths by mutableStateOf(emptyList<Pair<Path, PathProperties>>())
    var pathsUndone by mutableStateOf(emptyList<Pair<Path, PathProperties>>())
    var currentPosition by mutableStateOf(Offset.Unspecified)
    var previousPosition by mutableStateOf(Offset.Unspecified)
    var drawMode by mutableStateOf(DrawMode.Draw)
    var drawType by mutableStateOf(DrawType.Stylus)
    var currentPath by mutableStateOf(Path())
    var currentPathProperty by mutableStateOf(PathProperties())
    var textAnnotations by mutableStateOf(emptyList<TextAnnotation>())
    var selectedTextIndex by mutableIntStateOf(-1)

    val isEmpty: Boolean get() = paths.isEmpty() && textAnnotations.isEmpty()

    fun addPath(path: Path, properties: PathProperties) {
        paths = paths + (path to properties)
    }

    fun clearPathsUndone() {
        pathsUndone = emptyList()
    }

    fun undo() {
        val last = paths.lastOrNull() ?: return
        paths = paths.dropLast(1)
        pathsUndone = pathsUndone + last
    }

    fun redo() {
        val last = pathsUndone.lastOrNull() ?: return
        pathsUndone = pathsUndone.dropLast(1)
        paths = paths + last
    }

    fun changeDrawMode(mode: DrawMode) {
        currentPathProperty = currentPathProperty.copy(eraseMode = mode == DrawMode.Erase)
        drawMode = mode
    }

    fun changeDrawType(type: DrawType) {
        if (drawMode == DrawMode.Draw && drawType == type) return
        val p = currentPathProperty
        currentPathProperty = when (type) {
            DrawType.Stylus -> p.copy(
                strokeWidth = 20f, color = p.color.copy(alpha = 1f), strokeCap = StrokeCap.Round,
                brush = MarkupBrush.Solid, fillRegion = false
            )
            DrawType.Highlighter -> p.copy(
                strokeWidth = 30f, color = p.color.copy(alpha = 0.4f), strokeCap = StrokeCap.Square,
                brush = MarkupBrush.Solid, fillRegion = false
            )
            DrawType.Marker -> p.copy(
                strokeWidth = 40f, color = p.color.copy(alpha = 1f), strokeCap = StrokeCap.Round,
                brush = MarkupBrush.Solid, fillRegion = false
            )
            DrawType.Blur -> p.copy(
                strokeWidth = 60f, strokeCap = StrokeCap.Round, brush = MarkupBrush.Blur, fillRegion = false
            )
            DrawType.Mosaic -> p.copy(
                strokeWidth = 60f, strokeCap = StrokeCap.Round, brush = MarkupBrush.Mosaic, fillRegion = false
            )
        }
        drawType = type
    }

    /** Called after strokes/text were baked into the bitmap. */
    fun clear() {
        paths = emptyList()
        pathsUndone = emptyList()
        currentPath = Path()
        currentPathProperty = PathProperties()
        drawMode = DrawMode.Draw
        drawType = DrawType.Stylus
        textAnnotations = emptyList()
        selectedTextIndex = -1
    }
}
