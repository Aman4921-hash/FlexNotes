package com.example.ui

import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as CanvasStroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.database.NoteEntity
import com.example.data.model.Attachment
import com.example.data.model.Point
import com.example.data.model.Stroke
import com.example.ui.viewmodel.NoteViewModel
import kotlin.math.roundToInt
import kotlin.math.absoluteValue
import java.util.UUID
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.draw.scale

/* Utility state and functions for drawing */
private val strokePathCache = android.util.LruCache<String, Path>(2000)
private val outlinePathCache = android.util.LruCache<String, Path>(2000)

fun generateOutlinePath(stroke: Stroke, tool: String): Path {
    val path = Path()
    if (stroke.points.size < 2) return path
    
    val thickness = stroke.thickness
    val pts = ArrayList<Point>()
    // Filter points to reduce excessive vertices
    var lastP = stroke.points[0]
    pts.add(lastP)
    for (i in 1 until stroke.points.size) {
        val p = stroke.points[i]
        val dx = p.x - lastP.x
        val dy = p.y - lastP.y
        if (dx * dx + dy * dy >= 4f || i == stroke.points.size - 1) {
            pts.add(p)
            lastP = p
        }
    }
    if (pts.size < 2) return path

    val leftX = FloatArray(pts.size)
    val leftY = FloatArray(pts.size)
    val rightX = FloatArray(pts.size)
    val rightY = FloatArray(pts.size)

    var currentW = thickness
    for (i in pts.indices) {
        val p = pts[i]
        val prev = if (i > 0) pts[i - 1] else p
        val next = if (i < pts.size - 1) pts[i + 1] else p

        var dx = next.x - prev.x
        var dy = next.y - prev.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len > 0f) { dx /= len; dy /= len } else { dx = 1f; dy = 0f }

        val segPressure = (prev.pressure + p.pressure) / 2.0f
        
        val targetW = when (tool) {
            "PEN_FOUNTAIN" -> thickness * (0.35f + segPressure * 1.3f)
            "PEN_CALLIGRAPHY" -> {
                val angle = kotlin.math.atan2(dy, dx)
                val weight = kotlin.math.abs(kotlin.math.sin(angle - Math.PI / 4))
                thickness * (0.2f + weight.toFloat() * 1.8f) * (0.5f + segPressure * 0.7f)
            }
            "PEN_BALLPOINT" -> thickness * (0.6f + segPressure * 0.8f)
            "PEN_BRUSH" -> thickness * (0.5f + segPressure * 2.5f)
            else -> thickness
        }
        
        val w = if (i == 0) targetW else (currentW * 0.6f + targetW * 0.4f)
        currentW = w

        val nx = -dy
        val ny = dx
        val hw = w / 2f
        
        leftX[i] = p.x + nx * hw
        leftY[i] = p.y + ny * hw
        rightX[i] = p.x - nx * hw
        rightY[i] = p.y - ny * hw
    }

    path.moveTo(leftX[0], leftY[0])
    for (i in 1 until leftX.size) {
        path.lineTo(leftX[i], leftY[i])
    }
    // Connect right side
    path.lineTo(rightX.last(), rightY.last())
    for (i in rightX.size - 2 downTo 0) {
        path.lineTo(rightX[i], rightY[i])
    }
    path.close()
    return path
}

fun DrawScope.drawSpecialStroke(stroke: Stroke) {
    if (stroke.points.size < 2) return

    val tool = stroke.tool
    val color = Color(stroke.color)
    val thickness = stroke.thickness
    
    // Geometric shape drawing overlays
    if (stroke.shape != "NONE") {
        val first = stroke.points.first()
        val last = stroke.points.last()
        when (stroke.shape) {
            "RECTANGLE" -> {
                drawRect(
                    color = color,
                    topLeft = Offset(first.x, first.y),
                    size = androidx.compose.ui.geometry.Size(
                        width = last.x - first.x,
                        height = last.y - first.y
                    ),
                    style = CanvasStroke(width = thickness, cap = StrokeCap.Round)
                )
            }
            "CIRCLE" -> {
                val dx = last.x - first.x
                val dy = last.y - first.y
                val radius = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat() / 2f
                drawCircle(
                    color = color,
                    center = Offset(first.x + dx / 2, first.y + dy / 2),
                    radius = radius,
                    style = CanvasStroke(width = thickness)
                )
            }
            "LINE" -> {
                drawLine(
                    color = color,
                    start = Offset(first.x, first.y),
                    end = Offset(last.x, last.y),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round
                )
            }
            "ARROW" -> {
                drawLine(
                    color = color,
                    start = Offset(first.x, first.y),
                    end = Offset(last.x, last.y),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round
                )
                val angle = kotlin.math.atan2(last.y - first.y, last.x - first.x)
                val arrowLength = 30f
                val arrowAngle = Math.PI / 6
                val x1 = last.x - arrowLength * kotlin.math.cos(angle - arrowAngle).toFloat()
                val y1 = last.y - arrowLength * kotlin.math.sin(angle - arrowAngle).toFloat()
                val x2 = last.x - arrowLength * kotlin.math.cos(angle + arrowAngle).toFloat()
                val y2 = last.y - arrowLength * kotlin.math.sin(angle + arrowAngle).toFloat()
                drawLine(
                    color = color,
                    start = Offset(last.x, last.y),
                    end = Offset(x1, y1),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(last.x, last.y),
                    end = Offset(x2, y2),
                    strokeWidth = thickness,
                    cap = StrokeCap.Round
                )
            }
            "TRIANGLE" -> {
                val path = Path()
                path.moveTo(first.x + (last.x - first.x) / 2f, first.y)
                path.lineTo(first.x, last.y)
                path.lineTo(last.x, last.y)
                path.close()
                drawPath(
                    path = path,
                    color = color,
                    style = CanvasStroke(width = thickness, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
        return
    }

    // Creating a continuous Path for smooth rendering without overlap artifacts
    val isCurrent = stroke.id == "current"
    val cacheKey = if (isCurrent) "current_${stroke.points.size}" else stroke.id
    val pathCached = if (isCurrent) null else strokePathCache.get(cacheKey)
    val path = pathCached ?: run {
        val newPath = Path()
        if (stroke.points.isNotEmpty()) {
            newPath.moveTo(stroke.points.first().x, stroke.points.first().y)
            var lastX = stroke.points.first().x
            var lastY = stroke.points.first().y
            for (i in 1 until stroke.points.size) {
                val p = stroke.points[i]
                newPath.quadraticTo(lastX, lastY, (lastX + p.x) / 2, (lastY + p.y) / 2)
                lastX = p.x
                lastY = p.y
            }
            newPath.lineTo(lastX, lastY)
        }
        if (!isCurrent) strokePathCache.put(cacheKey, newPath)
        newPath
    }

    // Determine if stroke has significant pressure variation
    var isUniform = true
    if (stroke.points.isNotEmpty()) {
        val firstP = stroke.points.first().pressure
        for (i in 1 until stroke.points.size) {
            if (kotlin.math.abs(stroke.points[i].pressure - firstP) > 0.05f) {
                isUniform = false
                break
            }
        }
    }

    when (tool) {
        "PENCIL_STANDARD", "PENCIL_MECHANICAL", "PENCIL_SOFT", "PENCIL_SKETCH", "PENCIL_SHADING" -> {
            val baseAlpha = when (tool) {
                "PENCIL_STANDARD", "PENCIL_MECHANICAL" -> 0.8f
                "PENCIL_SOFT", "PENCIL_SKETCH" -> 0.45f
                else -> 0.15f
            }
            val baseWidth = when (tool) {
                "PENCIL_STANDARD", "PENCIL_MECHANICAL" -> thickness.coerceAtMost(4f)
                "PENCIL_SOFT", "PENCIL_SKETCH" -> thickness * 1.5f
                else -> thickness * 3f
            }
            // Transparent varying paths create ugly overlap "knots" if drawn as segments.
            // We use the continuous Path to ensure beautiful smooth graphite texture.
            drawPath(
                path = path,
                color = color.copy(alpha = baseAlpha),
                style = CanvasStroke(width = baseWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        "PEN_FOUNTAIN", "PEN_CALLIGRAPHY", "PEN_BRUSH", "PEN_BALLPOINT" -> {
            if (isUniform) {
                val scale = when (tool) {
                    "PEN_FOUNTAIN" -> 1.65f
                    "PEN_CALLIGRAPHY" -> 1.25f
                    "PEN_BRUSH" -> 1.5f
                    "PEN_BALLPOINT" -> 1.4f
                    else -> 1f
                }
                val pColor = if (tool == "PEN_BRUSH") color.copy(alpha = 0.8f) else color
                drawPath(path, pColor, style = CanvasStroke(width = thickness * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                if (isCurrent) {
                    var lastDrawn = stroke.points.first()
                    var lastW = thickness
                    for (i in 1 until stroke.points.size) {
                        val p = stroke.points[i]
                        val dx = p.x - lastDrawn.x
                        val dy = p.y - lastDrawn.y
                        if (dx * dx + dy * dy >= 4f || i == stroke.points.size - 1) {
                            val segPressure = (lastDrawn.pressure + p.pressure) / 2.0f
                            val targetW = when (tool) {
                                "PEN_FOUNTAIN" -> thickness * (0.35f + segPressure * 1.3f)
                                "PEN_CALLIGRAPHY" -> {
                                    val angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble())
                                    val weight = kotlin.math.abs(kotlin.math.sin(angle - Math.PI / 4))
                                    thickness * (0.2f + weight.toFloat() * 1.8f) * (0.5f + segPressure * 0.7f)
                                }
                                "PEN_BALLPOINT" -> thickness * (0.6f + segPressure * 0.8f)
                                "PEN_BRUSH" -> thickness * (0.5f + segPressure * 2.5f)
                                else -> thickness
                            }
                            val w = (lastW * 0.6f + targetW * 0.4f)
                            val alpha = if (tool == "PEN_BRUSH") 0.8f else 1.0f
                            drawLine(color.copy(alpha = alpha * color.alpha), Offset(lastDrawn.x, lastDrawn.y), Offset(p.x, p.y), strokeWidth = w, cap = StrokeCap.Round)
                            lastDrawn = p
                            lastW = w
                        }
                    }
                } else {
                    val fillPathCached = outlinePathCache.get(cacheKey)
                    val fillPath = fillPathCached ?: run {
                        val p = generateOutlinePath(stroke, tool)
                        outlinePathCache.put(cacheKey, p)
                        p
                    }
                    val pColor = if (tool == "PEN_BRUSH") color.copy(alpha = 0.8f) else color
                    drawPath(fillPath, pColor) // defaults to Fill
                }
            }
        }
        "PEN_NEON" -> {
            drawPath(
                path = path,
                color = color.copy(alpha = 0.35f),
                style = CanvasStroke(width = thickness * 3.5f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = path,
                color = color.copy(alpha = 0.15f),
                style = CanvasStroke(width = thickness * 7f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
            drawPath(
                path = path,
                color = Color.White,
                style = CanvasStroke(width = thickness * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        "PEN_BALLPOINT" -> {
            if (isUniform) {
                drawPath(path, color, style = CanvasStroke(width = thickness * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                var lastDrawn = stroke.points.first()
                for (i in 1 until stroke.points.size) {
                    val p = stroke.points[i]
                    val dx = p.x - lastDrawn.x
                    val dy = p.y - lastDrawn.y
                    if (dx * dx + dy * dy >= 9f || i == stroke.points.size - 1) { // 3px distance for ballpoint since it's smoother
                        val segPressure = (lastDrawn.pressure + p.pressure) / 2.0f
                        val w = thickness * (0.6f + segPressure * 0.8f)
                        drawLine(color, Offset(lastDrawn.x, lastDrawn.y), Offset(p.x, p.y), strokeWidth = w, cap = StrokeCap.Round)
                        lastDrawn = p
                    }
                }
            }
        }
        "PEN_FINELINER", "PEN_GEL" -> {
            drawPath(
                path = path,
                color = color,
                style = CanvasStroke(width = thickness, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
        "HIGHLIGHTER", "BRUSH_HIGHLIGHTER" -> {
            drawPath(
                path = path,
                color = color.copy(alpha = 0.45f),
                style = CanvasStroke(width = thickness, cap = StrokeCap.Square, join = StrokeJoin.Round)
            )
        }
        else -> {
            drawPath(
                path = path,
                color = color,
                style = CanvasStroke(width = thickness, cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}

@Composable
fun PdfPageRenderer(uriString: String, pageIndex: Int, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var bitmap by remember(uriString, pageIndex) { mutableStateOf<android.graphics.Bitmap?>(null) }
    
    LaunchedEffect(uriString, pageIndex) {
        withContext(Dispatchers.IO) {
            try {
                val pfd = if (uriString.startsWith("/")) {
                    android.os.ParcelFileDescriptor.open(java.io.File(uriString), android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                } else {
                    val uri = android.net.Uri.parse(uriString)
                    context.contentResolver.openFileDescriptor(uri, "r")
                }
                if (pfd != null) {
                    val renderer = android.graphics.pdf.PdfRenderer(pfd)
                    if (pageIndex >= 0 && pageIndex < renderer.pageCount) {
                        val page = renderer.openPage(pageIndex)
                        
                        // Limit maximum bitmap width/height to avoid OutOfMemoryError
                        val maxDimension = 2048f
                        val scaleX = maxDimension / page.width
                        val scaleY = maxDimension / page.height
                        val scale = minOf(scaleX, scaleY, 2.0f).coerceAtLeast(0.5f)
                        
                        val w = (page.width * scale).toInt().coerceAtLeast(1)
                        val h = (page.height * scale).toInt().coerceAtLeast(1)
                        
                        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                        page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap = bmp
                        page.close()
                    }
                    renderer.close()
                    pfd.close()
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }
    
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = "PDF Page $pageIndex",
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    } else {
        Box(
            modifier = modifier.fillMaxSize().background(Color.White),
            contentAlignment = androidx.compose.ui.Alignment.Center
        ) {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 1.5.dp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Rendering page ${pageIndex + 1}...", fontSize = 9.sp, color = Color.Gray)
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    title: String,
    colorInt: Int,
    onColorSelected: (Int) -> Unit,
    currentThickness: Float? = null,
    onThicknessSelected: ((Float) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    var opacity by remember { mutableStateOf(1f) }
    var sliderValue by remember { mutableStateOf(currentThickness ?: 4f) }
    
    val penPalettes = listOf(
        Color(0xFF000000) to -16777216,
        Color(0xFF1E88E5) to -14776347,
        Color(0xFFD81B60) to -2614432,
        Color(0xFFE53935) to -1754827,
        Color(0xFF8E24AA) to -7461718,
        Color(0xFF5E35B1) to -10603087,
        Color(0xFF00ACC1) to -16733247,
        Color(0xFF43A047) to -12345273,
        Color(0xFFFDD835) to -141259,
        Color(0xFFF4511E) to -765666
    )
    
    val palettes = penPalettes

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                title, 
                fontWeight = FontWeight.Bold, 
                fontSize = 18.sp
            )
            
            Text("Presets", fontSize = 14.sp, color = Color.Gray)
            LazyVerticalGrid(
                columns = GridCells.Fixed(5),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                items(palettes) { (colorState, rawVal) ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(colorState)
                            .border(
                                if (colorInt == rawVal) 3.dp else 1.dp,
                                if (colorInt == rawVal) MaterialTheme.colorScheme.primary else Color.LightGray,
                                CircleShape
                            )
                            .clickable {
                                onColorSelected(rawVal)
                                onDismiss()
                            }
                    )
                }
            }
            
            if (currentThickness != null && onThicknessSelected != null) {
                Spacer(modifier = Modifier.height(16.dp))
                
                Text("Thickness: ${sliderValue.toInt()} dp", fontSize = 14.sp, color = Color.Gray)
                Slider(
                    value = sliderValue,
                    onValueChange = { 
                        sliderValue = it
                        onThicknessSelected(it)
                    },
                    valueRange = 0.1f..70f,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAttachmentSheet(
    onDismiss: () -> Unit,
    onConfirm: (type: String, title: String, url: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("PDF") } // "PDF" or "IMAGE"
    val context = androidx.compose.ui.platform.LocalContext.current
    var fileUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val imageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            fileUri = uri
            selectedType = "IMAGE"
            val lastSegment = uri.lastPathSegment ?: "image"
            title = if (lastSegment.contains("/")) lastSegment.substringAfterLast("/") else lastSegment
        }
    }

    val pdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            fileUri = uri
            selectedType = "PDF"
            val lastSegment = uri.lastPathSegment ?: "document"
            title = if (lastSegment.contains("/")) lastSegment.substringAfterLast("/") else lastSegment
        }
    }

    val samplesList = listOf(
        "Lecture Workbook Proof 3" to "PDF",
        "Calculus Trig Formulas Chart" to "PDF",
        "Structure Of Organelles diagram" to "IMAGE",
        "Atmosphere Carbon Cycles diagram" to "IMAGE",
        "Periodic Grid Reference Chart" to "PDF"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert File Attachment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Type Choice
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ElevatedAssistChip(
                        onClick = { selectedType = "PDF" },
                        leadingIcon = {
                            if (selectedType == "PDF") Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("PDF Document") },
                        colors = if (selectedType == "PDF") {
                            AssistChipDefaults.elevatedAssistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else {
                            AssistChipDefaults.elevatedAssistChipColors()
                        }
                    )

                    ElevatedAssistChip(
                        onClick = { selectedType = "IMAGE" },
                        leadingIcon = {
                            if (selectedType == "IMAGE") Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        label = { Text("Image Diagram") },
                        colors = if (selectedType == "IMAGE") {
                            AssistChipDefaults.elevatedAssistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        } else {
                            AssistChipDefaults.elevatedAssistChipColors()
                        }
                    )
                }

                Text("Select Device Local File:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { imageLauncher.launch("image/*") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pick Image", fontSize = 10.sp)
                    }

                    Button(
                        onClick = { pdfLauncher.launch("application/pdf") },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pick PDF", fontSize = 10.sp)
                    }
                }

                if (fileUri != null) {
                    Text(
                        "Chosen file: ${fileUri?.lastPathSegment}",
                        fontSize = 11.sp,
                        color = Color(0xFF16A34A),
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Attachment Title") },
                    placeholder = { Text("e.g. Heart Anatomy Slide") },
                    modifier = Modifier.fillMaxWidth()
                )

                // Curated samples pickers
                Text("Pre-loaded School Study Files:", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    samplesList.forEach { (name, type) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    title = name
                                    selectedType = type
                                    fileUri = null
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (type == "PDF") Color(0xFFFEE2E2) else Color(0xFFE6F4EA)
                                ),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                  Text(
                                    text = type,
                                    fontSize = 8.sp,
                                    color = if (type == "PDF") Color(0xFFDC2626) else Color(0xFF137333),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (title.isNotBlank()) onConfirm(selectedType, title, fileUri?.toString() ?: "") },
                enabled = title.isNotBlank()
            ) {
                Text("Insert onto Page")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PagesGridOverviewDialog(
    pages: List<com.example.data.database.NotePageEntity>,
    currentPageIndex: Int,
    onSelectPage: (Int) -> Unit,
    onDismiss: () -> Unit,
    viewModel: com.example.ui.viewmodel.NoteViewModel
) {
    val localDensity = androidx.compose.ui.platform.LocalDensity.current
    val curDensity = localDensity.density
    val activeNoteEntity = viewModel.activeNote.collectAsState().value
    val pressureSensitivityEnabled by viewModel.pressureSensitivityEnabled.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Page Navigation and Sorter Grid", fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Cancel, contentDescription = "Close")
                }
            }
        },
        text = {
            Column {
                Text(
                    text = "Pick any page to instantly navigate. Showing all ${pages.size} pages.",
                    fontSize = 12.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                ) {
                    items(pages.size) { index ->
                        val isSelected = index == currentPageIndex
                        val page = pages[index]
                        val strokesList = remember(page.drawingDataJson) {
                            try {
                                val jArray = JSONArray(page.drawingDataJson)
                                val sList = mutableListOf<Stroke>()
                                for (i in 0 until jArray.length()) {
                                    sList.add(Stroke.fromJson(jArray.getJSONObject(i)))
                                }
                                sList
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                        val attachmentsList = remember(page.attachmentsJson) {
                            try {
                                Attachment.listFromJson(page.attachmentsJson)
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }

                        val isSorting by viewModel.isSorting.collectAsState()

                        Box(
                            modifier = Modifier
                                .aspectRatio(0.75f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.White)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { onSelectPage(index) },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize().padding(2.dp)) {
                                val isLandscape = activeNoteEntity?.isLandscape == true
                                val targetWidthPx = (if (isLandscape) 1754f else 1240f) * curDensity
                                val targetHeightPx = (if (isLandscape) 1240f else 1754f) * curDensity
                                val scaleX = size.width / targetWidthPx
                                val scaleY = size.height / targetHeightPx
                                val scale = minOf(scaleX, scaleY)
                                withTransform({
                                    scale(scale, scale, Offset.Zero)
                                }) {
                                    strokesList.forEach { stroke ->
                                        drawSpecialStroke(stroke)
                                    }
                                    attachmentsList.forEach { attachment ->
                                        // Simple placeholder for attachment in thumbnail
                                        val wPx = attachment.width * curDensity
                                        val hPx = attachment.height * curDensity
                                        drawRect(
                                            color = Color.LightGray,
                                            topLeft = Offset(attachment.x, attachment.y),
                                            size = androidx.compose.ui.geometry.Size(wPx, hPx),
                                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f / scale) // Adjust stroke width for scale
                                        )
                                    }
                                }
                            }
                            
                            // Reorder buttons
                            Column(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                                if (index > 0) {
                                    IconButton(
                                        onClick = { viewModel.movePage(index, index - 1) }, 
                                        modifier = Modifier.size(24.dp),
                                        enabled = !isSorting
                                    ) {
                                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up")
                                    }
                                }
                                if (index < pages.size - 1) {
                                    IconButton(
                                        onClick = { viewModel.movePage(index, index + 1) }, 
                                        modifier = Modifier.size(24.dp),
                                        enabled = !isSorting
                                    ) {
                                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down")
                                    }
                                }
                            }
                            
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.5f))
                                    .padding(vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Page ${index + 1}",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageBorderCustomizationToolbar(
    selectedAttachment: Attachment,
    onUpdate: (Attachment) -> Unit,
    onDismiss: () -> Unit
) {
    var activeTab by remember { mutableStateOf(0) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(12.dp, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
        ),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.FilterFrames,
                        contentDescription = "Borders",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Customize Photo Frame",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Border Active", fontSize = 11.sp, color = Color.Gray)
                    Switch(
                        checked = selectedAttachment.hasBorder,
                        onCheckedChange = { onUpdate(selectedAttachment.copy(hasBorder = it)) },
                        modifier = Modifier.scale(0.8f)
                    )
                    
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Toolbar",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (selectedAttachment.hasBorder) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Styles & Layout Preset:",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                
                val styles = listOf(
                    "SOLID" to "Solid",
                    "ROUNDED" to "Rounded",
                    "SHADOW" to "Shadowed",
                    "POLAROID" to "Polaroid",
                    "NOTEBOOK" to "Notebook",
                    "TAPE" to "Paper Attached",
                    "MINIMAL" to "Minimalist",
                    "ARTISTIC" to "Artistic Duo"
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(styles) { (styleId, label) ->
                        val isSelected = selectedAttachment.borderStyle == styleId
                        FilterChip(
                            selected = isSelected,
                            onClick = { onUpdate(selectedAttachment.copy(borderStyle = styleId)) },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                TabRow(
                    selectedTabIndex = activeTab,
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[activeTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                ) {
                    Tab(
                        selected = activeTab == 0,
                        onClick = { activeTab = 0 },
                        text = { Text("Thickness & Spacing", fontSize = 11.sp) }
                    )
                    Tab(
                        selected = activeTab == 1,
                        onClick = { activeTab = 1 },
                        text = { Text("Corners & Shadow", fontSize = 11.sp) }
                    )
                    Tab(
                        selected = activeTab == 2,
                        onClick = { activeTab = 2 },
                        text = { Text("Colors & Opacity", fontSize = 11.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (activeTab) {
                    0 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Thickness: ${selectedAttachment.borderThickness.toInt()} dp", fontSize = 10.sp, modifier = Modifier.width(90.dp))
                                Slider(
                                    value = selectedAttachment.borderThickness,
                                    onValueChange = { onUpdate(selectedAttachment.copy(borderThickness = it)) },
                                    valueRange = 1f..24f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Padding: ${selectedAttachment.borderPadding.toInt()} dp", fontSize = 10.sp, modifier = Modifier.width(90.dp))
                                Slider(
                                    value = selectedAttachment.borderPadding,
                                    onValueChange = { onUpdate(selectedAttachment.copy(borderPadding = it)) },
                                    valueRange = 0f..24f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    1 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Radius: ${selectedAttachment.borderCornerRadius.toInt()} dp", fontSize = 10.sp, modifier = Modifier.width(90.dp))
                                Slider(
                                    value = selectedAttachment.borderCornerRadius,
                                    onValueChange = { onUpdate(selectedAttachment.copy(borderCornerRadius = it)) },
                                    valueRange = 0f..40f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Shadow: ${selectedAttachment.shadowSize.toInt()} dp", fontSize = 10.sp, modifier = Modifier.width(90.dp))
                                Slider(
                                    value = selectedAttachment.shadowSize,
                                    onValueChange = { onUpdate(selectedAttachment.copy(shadowSize = it)) },
                                    valueRange = 0f..24f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    2 -> {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("Transparency: ${(selectedAttachment.borderOpacity * 100).toInt()}%", fontSize = 10.sp, modifier = Modifier.width(110.dp))
                                Slider(
                                    value = selectedAttachment.borderOpacity,
                                    onValueChange = { onUpdate(selectedAttachment.copy(borderOpacity = it)) },
                                    valueRange = 0.1f..1.0f,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            val borderColors = listOf(
                                -16777216,
                                -1,
                                0xFFFFFDD0.toInt(),
                                0xFFD4AF37.toInt(),
                                0xFF3F51B5.toInt(),
                                0xFFEF5350.toInt(),
                                0xFF66BB6A.toInt(),
                                0xFF00E5FF.toInt(),
                                0xFFE040FB.toInt()
                            )
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Color:", fontSize = 10.sp)
                                borderColors.forEach { col ->
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(CircleShape)
                                            .background(Color(col))
                                            .border(
                                                width = if (selectedAttachment.borderColor == col) 2.dp else 1.dp,
                                                color = if (selectedAttachment.borderColor == col) MaterialTheme.colorScheme.primary else Color.LightGray,
                                                shape = CircleShape
                                            )
                                            .clickable { onUpdate(selectedAttachment.copy(borderColor = col)) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Enable border active to begin styling with decorative frames.",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        fontStyle = FontStyle.Italic
                    )
                }
            }
        }
    }
}


@Composable
fun DecoratedImage(
    attachment: Attachment,
    modifier: Modifier = Modifier
) {
    if (!attachment.hasBorder) {
        AsyncImage(
            model = attachment.path,
            contentDescription = attachment.name,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
        return
    }

    val color = Color(attachment.borderColor).copy(alpha = attachment.borderOpacity)
    val thickness = attachment.borderThickness.dp
    val padding = attachment.borderPadding.dp
    val radius = attachment.borderCornerRadius.dp
    val shadow = 0.dp // Forced flat to eliminate detached/floating layers, feels printed directly on paper book

    when (attachment.borderStyle) {
        "SOLID" -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .border(thickness, color, RectangleShape)
                    .padding(thickness)
                    .background(Color.White)
                    .padding(padding)
            ) {
                AsyncImage(
                    model = attachment.path,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        "ROUNDED" -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .border(thickness, color, RoundedCornerShape(radius))
                    .padding(thickness)
                    .clip(RoundedCornerShape(radius))
                    .background(Color.White)
                    .padding(padding)
            ) {
                AsyncImage(
                    model = attachment.path,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        "SHADOW" -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .shadow(elevation = shadow, shape = RoundedCornerShape(radius))
                    .background(Color.White, RoundedCornerShape(radius))
                    .border(thickness, color, RoundedCornerShape(radius))
                    .padding(thickness)
                    .clip(RoundedCornerShape(radius))
                    .padding(padding)
            ) {
                AsyncImage(
                    model = attachment.path,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        "POLAROID" -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .shadow(elevation = shadow, shape = RoundedCornerShape(4.dp))
                    .background(color, RoundedCornerShape(4.dp))
                    .border(thickness, Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 48.dp)
                    .background(Color.White)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = attachment.path,
                        contentDescription = attachment.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .offset(y = 42.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = if (attachment.name.length > 20) attachment.name.take(17) + "..." else attachment.name,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.DarkGray,
                        style = androidx.compose.ui.text.TextStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                        ),
                        maxLines = 1
                    )
                }
            }
        }
        "NOTEBOOK" -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .shadow(elevation = shadow, shape = RoundedCornerShape(4.dp))
                    .background(Color.White, RoundedCornerShape(4.dp))
                    .border(thickness, color, RoundedCornerShape(4.dp))
                    .drawBehind {
                        val marginX = 24.dp.toPx()
                        drawLine(
                            color = Color(0xFFFF4D4D),
                            start = androidx.compose.ui.geometry.Offset(marginX, 0f),
                            end = androidx.compose.ui.geometry.Offset(marginX, size.height),
                            strokeWidth = 1.5.dp.toPx()
                        )
                        val holeRadius = 3.dp.toPx()
                        val spacing = 16.dp.toPx()
                        var currentY = 12.dp.toPx()
                        while (currentY < size.height) {
                            drawCircle(
                                color = Color.LightGray,
                                radius = holeRadius,
                                center = androidx.compose.ui.geometry.Offset(10.dp.toPx(), currentY)
                            )
                            drawArc(
                                color = Color(0xFF7F8C8D),
                                startAngle = -45f,
                                sweepAngle = 180f,
                                useCenter = false,
                                topLeft = androidx.compose.ui.geometry.Offset(0f, currentY - holeRadius),
                                size = androidx.compose.ui.geometry.Size(12.dp.toPx(), holeRadius * 2),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                            )
                            currentY += spacing
                        }
                    }
                    .padding(start = 32.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)
                    .padding(padding)
            ) {
                AsyncImage(
                    model = attachment.path,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        "MINIMAL" -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(8.dp)
                    .drawBehind {
                        val strokePx = 2.dp.toPx()
                        val l = 16.dp.toPx()
                        drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(l, 0f), strokeWidth = strokePx)
                        drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, l), strokeWidth = strokePx)

                        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width - l, 0f), strokeWidth = strokePx)
                        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, 0f), androidx.compose.ui.geometry.Offset(size.width, l), strokeWidth = strokePx)

                        drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(l, size.height), strokeWidth = strokePx)
                        drawLine(color, androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(0f, size.height - l), strokeWidth = strokePx)

                        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, size.height), androidx.compose.ui.geometry.Offset(size.width - l, size.height), strokeWidth = strokePx)
                        drawLine(color, androidx.compose.ui.geometry.Offset(size.width, size.height), androidx.compose.ui.geometry.Offset(size.width, size.height - l), strokeWidth = strokePx)
                    }
                    .padding(thickness)
                    .padding(padding)
            ) {
                AsyncImage(
                    model = attachment.path,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        "ARTISTIC" -> {
            val brush = androidx.compose.ui.graphics.Brush.linearGradient(
                colors = listOf(
                    color,
                    Color(0xFFE040FB),
                    Color(0xFF00E5FF)
                )
            )
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .shadow(elevation = shadow, shape = RoundedCornerShape(radius))
                    .background(Color.White, RoundedCornerShape(radius))
                    .border(thickness, brush, RoundedCornerShape(radius))
                    .padding(thickness)
                    .clip(RoundedCornerShape(radius))
                    .padding(padding)
            ) {
                AsyncImage(
                    model = attachment.path,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        "TAPE" -> {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .border(thickness, color, RectangleShape)
                    .padding(thickness)
                    .padding(padding)
                    .drawBehind {
                        // Draw a semi-transparent cream tape at the top center of the attached sheet
                        val tapeWidth = 60.dp.toPx()
                        val tapeHeight = 16.dp.toPx()
                        val startX = (size.width - tapeWidth) / 2
                        val startY = -4.dp.toPx()
                        
                        // Rotate the tape slightly for craft organic look
                        rotate(degrees = -3f, pivot = androidx.compose.ui.geometry.Offset(size.width / 2, 0f)) {
                            drawRect(
                                color = Color(0xFFFCF3CF).copy(alpha = 0.85f), // parchment washi cream tape
                                topLeft = androidx.compose.ui.geometry.Offset(startX, startY),
                                size = androidx.compose.ui.geometry.Size(tapeWidth, tapeHeight)
                            )
                            // Subtle jagged paper tape borders
                            drawLine(
                                color = Color(0xFFD4AC0D).copy(alpha = 0.4f),
                                start = androidx.compose.ui.geometry.Offset(startX, startY),
                                end = androidx.compose.ui.geometry.Offset(startX, startY + tapeHeight),
                                strokeWidth = 1f
                            )
                            drawLine(
                                color = Color(0xFFD4AC0D).copy(alpha = 0.4f),
                                start = androidx.compose.ui.geometry.Offset(startX + tapeWidth, startY),
                                end = androidx.compose.ui.geometry.Offset(startX + tapeWidth, startY + tapeHeight),
                                strokeWidth = 1f
                            )
                        }
                    }
            ) {
                AsyncImage(
                    model = attachment.path,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
        }
        else -> {
            AsyncImage(
                model = attachment.path,
                contentDescription = attachment.name,
                modifier = modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}


@Composable
fun DraggableAttachmentCard(
    attachment: Attachment,
    isEditorMode: Boolean,
    isSelected: Boolean = false,
    onSelect: () -> Unit = {},
    onUpdate: (Attachment) -> Unit,
    onDelete: () -> Unit
) {
    var offsetX by remember { mutableStateOf(attachment.x) }
    var offsetY by remember { mutableStateOf(attachment.y) }
    var width by remember { mutableStateOf(attachment.width) }
    var height by remember { mutableStateOf(attachment.height) }

    LaunchedEffect(attachment.x, attachment.y, attachment.width, attachment.height) {
        offsetX = attachment.x
        offsetY = attachment.y
        width = attachment.width
        height = attachment.height
    }

    val isPdf = attachment.type == "PDF"
    val isImage = attachment.type == "IMAGE" || attachment.type == "PDF_PAGE"
    val density = androidx.compose.ui.platform.LocalDensity.current
    val aspectRatio = remember(attachment) { if (attachment.height > 0f) attachment.width / attachment.height else 1f }

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .size(width.dp, height.dp)
            .run {
                if (isImage) {
                    this.clickable(enabled = isEditorMode) { onSelect() }
                        .let {
                            if (isSelected) {
                                it.border(1.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                it
                            }
                        }
                } else {
                    this.shadow(if (isSelected) 8.dp else 4.dp, RoundedCornerShape(8.dp))
                        .background(if (isPdf) Color(0xFFFEF2F2) else Color(0xFFF9FAF3), RoundedCornerShape(8.dp))
                        .border(
                            width = if (isSelected) 3.dp else if (isEditorMode) 2.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else if (isEditorMode) MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f) else Color.LightGray.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = isEditorMode) { onSelect() }
                }
            }
            .then(
                if (isEditorMode) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y
                            },
                            onDragEnd = {
                                onUpdate(attachment.copy(x = offsetX, y = offsetY))
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        // Toolbar for deletion or type actions
        if (isSelected && isEditorMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-40).dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }

            // Top-Center Drag handle icon (press & hold or drag to move explicitly!)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = (-40).dp)
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                offsetX += dragAmount.x
                                offsetY += dragAmount.y
                            },
                            onDragEnd = {
                                onUpdate(attachment.copy(x = offsetX, y = offsetY))
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.OpenWith,
                    contentDescription = "Hold to Move",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp)
                )
            }

            // 4 Corner Resize Handles (Resize proportionally from any corner!)
            // 1. Top-Left Handle
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(x = (-12).dp, y = (-12).dp)
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dxDp = with(density) { dragAmount.x.toDp().value }
                                val newWidth = (width - dxDp).coerceAtLeast(40f)
                                val newHeight = newWidth / aspectRatio
                                val changeWidthPx = with(density) { (newWidth - width).dp.toPx() }
                                val changeHeightPx = with(density) { (newHeight - height).dp.toPx() }
                                offsetX -= changeWidthPx
                                offsetY -= changeHeightPx
                                width = newWidth
                                height = newHeight
                            },
                            onDragEnd = {
                                onUpdate(attachment.copy(x = offsetX, y = offsetY, width = width, height = height))
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color.White, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
            }

            // 2. Top-Right Handle
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 12.dp, y = (-12).dp)
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dxDp = with(density) { dragAmount.x.toDp().value }
                                val newWidth = (width + dxDp).coerceAtLeast(40f)
                                val newHeight = newWidth / aspectRatio
                                val changeHeightPx = with(density) { (newHeight - height).dp.toPx() }
                                offsetY -= changeHeightPx
                                width = newWidth
                                height = newHeight
                            },
                            onDragEnd = {
                                onUpdate(attachment.copy(y = offsetY, width = width, height = height))
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color.White, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
            }

            // 3. Bottom-Left Handle
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .offset(x = (-12).dp, y = 12.dp)
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dxDp = with(density) { dragAmount.x.toDp().value }
                                val newWidth = (width - dxDp).coerceAtLeast(40f)
                                val newHeight = newWidth / aspectRatio
                                val changeWidthPx = with(density) { (newWidth - width).dp.toPx() }
                                offsetX -= changeWidthPx
                                width = newWidth
                                height = newHeight
                            },
                            onDragEnd = {
                                onUpdate(attachment.copy(x = offsetX, width = width, height = height))
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color.White, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
            }

            // 4. Bottom-Right Handle
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 12.dp, y = 12.dp)
                    .size(24.dp)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dxDp = with(density) { dragAmount.x.toDp().value }
                                val newWidth = (width + dxDp).coerceAtLeast(40f)
                                val newHeight = newWidth / aspectRatio
                                width = newWidth
                                height = newHeight
                            },
                            onDragEnd = {
                                onUpdate(attachment.copy(width = width, height = height))
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(Color.White, CircleShape)
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                )
            }
        }

        // Body Display
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (attachment.type == "PDF_PAGE") {
                PdfPageRenderer(
                    uriString = attachment.path,
                    pageIndex = attachment.pdfPageIndex,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (attachment.type == "IMAGE" && attachment.path.isNotEmpty()) {
                DecoratedImage(
                    attachment = attachment,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (isPdf) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(6.dp)
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(36.dp))
                    Text(
                        text = "Reference Courseware Document",
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        color = Color.Gray,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
fun ToolSettingsPanel(
    thickness: Float,
    onThicknessChange: (Float) -> Unit,
    color: Color,
    onColorChange: (Color) -> Unit,
    onMoreColors: () -> Unit,
    activeToolName: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings: ${activeToolName.uppercase()}", fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                IconButton(onClick = onMoreColors, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Palette, contentDescription = "More Colors", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Thick (${"%.0f".format(thickness)}dp)", modifier = Modifier.padding(end = 8.dp), fontSize = 10.sp)
                Slider(
                    value = thickness,
                    onValueChange = onThicknessChange,
                    valueRange = 1f..70f,
                    modifier = Modifier.weight(1f).height(24.dp)
                )
            }
            val colors = listOf(Color.Black, Color.DarkGray, Color.Gray, Color.LightGray, Color.White, Color.Red, Color.Blue, Color.Green, Color.Yellow, Color.Magenta, Color.Cyan)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                items(colors) { c ->
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(c)
                            .border(1.dp, if (color == c) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                            .clickable { onColorChange(c) }
                    )
                }
            }
        }
    }
}

@Composable
fun PageNavigationPanel(
    currentPageIndex: Int,
    totalPageCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAddPage: () -> Unit,
    onDeletePage: () -> Unit,
    onShowGrid: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, enabled = currentPageIndex > 0, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Prev")
                }
                Text(
                    text = "${currentPageIndex + 1} / $totalPageCount",
                    modifier = Modifier.clickable { onShowGrid() }.padding(horizontal = 12.dp),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                IconButton(onClick = onNext, enabled = currentPageIndex < totalPageCount - 1, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next")
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onDeletePage, modifier = Modifier.size(36.dp)) { 
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp)) 
                }
                TextButton(onClick = onAddPage, shape = RoundedCornerShape(8.dp)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Add Page", fontSize = 12.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CanvasScreen(
    viewModel: NoteViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activeNote by viewModel.activeNote.collectAsState()
    val notePages by viewModel.activeNotePages.collectAsState()
    val currentPageIndex by viewModel.currentPageIndex.collectAsState()

    val currentStrokes by viewModel.activeStrokes.collectAsState()
    val currentAttachments by viewModel.activeAttachments.collectAsState()

    val tool by viewModel.activeTool.collectAsState()
    val shape by viewModel.activeShape.collectAsState()
    val colorInt by viewModel.activeColor.collectAsState()
    val thickness by viewModel.activeThickness.collectAsState()
    val isFullScreen by viewModel.isFullScreen.collectAsState()
    val activePenSubTool by viewModel.activePenSubTool.collectAsState()
    val pressureSensitivityEnabled by viewModel.pressureSensitivityEnabled.collectAsState()
    val toolForPicker = if (tool == "PEN" && activePenSubTool.contains("PENCIL")) "PENCIL" else tool
    val temporaryHighlightedStrokes by viewModel.temporaryHighlightedStrokes.collectAsState()
    val lassoSelectedStrokes by viewModel.lassoSelectedStrokes.collectAsState()
    val lassoClipboardStrokes by viewModel.lassoClipboardStrokes.collectAsState()
    val lassoClipboardAttachments by viewModel.lassoClipboardAttachments.collectAsState()
    val isLandscape = activeNote?.isLandscape ?: false
    val coroutineScope = rememberCoroutineScope()

    val stylusOnly by viewModel.stylusOnlyMode.collectAsState()
    val zoom by viewModel.zoomScale.collectAsState()
    val pan by viewModel.panOffset.collectAsState()
    val paperGridSpacing by viewModel.paperGridSpacing.collectAsState()
    val ruleGridThickness by viewModel.ruleGridThickness.collectAsState()
    val graphGridThickness by viewModel.graphGridThickness.collectAsState()
    val dotGridThickness by viewModel.dotGridThickness.collectAsState()
    val strokeStabilization by viewModel.strokeStabilization.collectAsState()
    val backgroundColor by viewModel.canvasBackgroundColor.collectAsState()
    val noteBackgroundColor by viewModel.noteBackgroundColor.collectAsState()
    val gridColor by viewModel.gridColor.collectAsState()

    val isReadingMode by viewModel.isReadingMode.collectAsState()
    var showPagesGridDialog by remember { mutableStateOf(false) }
    var showThicknessSlider by remember { mutableStateOf(false) }
    var showDrawingSettingsPanel by remember { mutableStateOf(false) }
    var showPageNavigationCentre by remember { mutableStateOf(false) }
    var activeToolMenu by remember { mutableStateOf<String?>(null) }

    var colorPickerTarget by remember { mutableStateOf<String?>(null) }
    var showAttachmentDialog by remember { mutableStateOf(false) }
    var selectedAttachmentId by remember { mutableStateOf<String?>(null) }

    val directImageLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val lastSegment = uri.lastPathSegment ?: "image"
            val title = if (lastSegment.contains("/")) lastSegment.substringAfterLast("/") else lastSegment
            val localPath = viewModel.copyExternalUriToLocal(uri, "png")
            viewModel.addAttachment(
                Attachment(
                    id = java.util.UUID.randomUUID().toString(),
                    name = title,
                    type = "IMAGE",
                    path = localPath,
                    x = 100f,
                    y = 150f
                )
            )
        }
    }

    val directPdfLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val lastSegment = uri.lastPathSegment ?: "document"
            val title = if (lastSegment.contains("/")) lastSegment.substringAfterLast("/") else lastSegment
            val localPath = viewModel.copyExternalUriToLocal(uri, "pdf")
            viewModel.insertPageWithPdf(localPath, title)
        }
    }

    // Navigation and Workspace selection
    var modeDrawOrPan by remember { mutableStateOf(true) } // true = Draw, false = Navigation/Move
    var eraserCursorPosition by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }

    LaunchedEffect(modeDrawOrPan) {
        if (modeDrawOrPan) {
            selectedAttachmentId = null
        }
    }

    var isToolbarExpanded by remember { mutableStateOf(false) }
    var showMoreUtilities by remember { mutableStateOf(false) }
    var bottomBarHeight by remember { mutableStateOf(0f) }
    val expansionFraction by animateFloatAsState(
        targetValue = if (isToolbarExpanded) 1f else 0.4f,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "Expansion"
    )

    val activeNoteEntity = activeNote ?: return

    // Screen shell
    Scaffold(
        topBar = {
            if (!isFullScreen) {
                TopAppBar(
                    title = {
                        Column {
                        Text(
                            text = activeNoteEntity.title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isReadingMode) {
                            Text(
                                text = "Reading Mode Active 📖",
                                fontSize = 11.sp,
                                color = Color(0xFF2E7D32),
                                style = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFF7F2FA)
                ),
                actions = {
                    // Reading Mode toggle
                    IconButton(
                        onClick = {
                            viewModel.isReadingMode.value = !isReadingMode
                            if (viewModel.isReadingMode.value) {
                                modeDrawOrPan = false
                            } else {
                                modeDrawOrPan = true
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isReadingMode) Icons.Default.MenuBook else Icons.Default.MenuBook,
                            contentDescription = "Toggle Reading Mode",
                            tint = if (isReadingMode) Color(0xFF6750A4) else Color(0xFF49454F)
                        )
                    }

                    // Undo, Redo Actions
                    IconButton(
                        onClick = { viewModel.undoLastStroke() },
                        enabled = !isReadingMode
                    ) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo", tint = if (isReadingMode) Color.LightGray else Color(0xFF49454F))
                    }
                    IconButton(
                        onClick = { viewModel.redoLastStroke() },
                        enabled = !isReadingMode
                    ) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo", tint = if (isReadingMode) Color.LightGray else Color(0xFF49454F))
                    }

                    // Mode Toggles
                    IconButton(
                        onClick = { modeDrawOrPan = !modeDrawOrPan },
                        enabled = !isReadingMode,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (modeDrawOrPan) Color.Transparent else Color(0xFFEADDFF)
                        )
                    ) {
                        Icon(
                            imageVector = if (modeDrawOrPan) Icons.Default.Gesture else Icons.Default.PanTool,
                            contentDescription = "Draw vs Navigation Mode",
                            tint = if (isReadingMode) Color.LightGray else if (modeDrawOrPan) Color(0xFF49454F) else Color(0xFF21005D)
                        )
                    }

                    // PDF export trigger
                    Button(
                        onClick = { viewModel.exportNoteAsPdf() },
                        modifier = Modifier.padding(end = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6750A4),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export", fontSize = 12.sp)
                    }
                }
            )
            }
        },
        bottomBar = {
            if (isFullScreen) {
                // Hides entirely in distraction-free writing mode
            } else if (isReadingMode) {
                Surface(
                    tonalElevation = 6.dp,
                    color = Color(0xFFEADDFF).copy(alpha = 0.95f),
                    modifier = Modifier.fillMaxWidth().wrapContentHeight()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = Color(0xFF21005D))
                            Text("Reading Mode Active", fontWeight = FontWeight.Bold, color = Color(0xFF21005D), fontSize = 14.sp)
                        }
                        Button(
                            onClick = {
                                viewModel.isReadingMode.value = false
                                modeDrawOrPan = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4))
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit Note")
                        }
                    }
                }
            } else {
                // Toolbar overlay will be used instead of bottomBar slot for peeking effect
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullScreen) PaddingValues(0.dp) else innerPadding)
        ) {

            // Setup drawing state tracking
            var currentPoints = remember { mutableStateListOf<Point>() }

            // Transform zoom handle (Two-finger pinch Zoom in and out enabled for all modes)
            val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
                viewModel.zoomScale.value = (zoom * zoomChange).coerceIn(0.1f, 5.0f)
                viewModel.panOffset.value = pan + panChange
            }

            // Canvas Workspace Wrapper
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(backgroundColor))
                    .transformable(state = transformableState)
            ) {
                // Determine paper size
                val isLandscape = activeNoteEntity.isLandscape
                
                val pageWidthDP = (if (isLandscape) 1754f else 1240f).dp
                val pageHeightDP = (if (isLandscape) 1240f else 1754f).dp

                // Initial zoom fit logic
                LaunchedEffect(activeNoteEntity.id) {
                    if (viewModel.zoomScale.value == 1.0f || viewModel.zoomScale.value == 0f) {
                        // Fit to ensure whole page is visible
                        val fitZoomWidth = maxWidth / pageWidthDP
                        val fitZoomHeight = maxHeight / pageHeightDP
                        val fitZoom = minOf(fitZoomWidth, fitZoomHeight)
                        viewModel.zoomScale.value = fitZoom.coerceIn(0.1f, 5.0f).let { if (it > 0) it else 0.5f }
                    }
                }

                // Scaled inner page sheet drawing grid (Zoom and pan applied uniformly to layout)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = pan.x,
                            translationY = pan.y
                        )
                ) {
                    // White paper boundaries container (A4 standard dimensions)
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .requiredSize(width = pageWidthDP, height = pageHeightDP)
                            .background(Color(noteBackgroundColor), RoundedCornerShape(2.dp))
                            .shadow(if (isFullScreen) 1.dp else 4.dp)
                            .pointerInput(modeDrawOrPan, tool, colorInt, thickness, shape, isReadingMode, activePenSubTool, pressureSensitivityEnabled) {
                                if (modeDrawOrPan && !isReadingMode) {
                                    val scope = this
                                    awaitEachGesture {
                                        val downEvent = awaitFirstDown(requireUnconsumed = false)

                                        // Cancel if multitouch is starting to allow clean gesture zoom/pan
                                        if (currentEvent.changes.size > 1) {
                                            currentPoints.clear()
                                            return@awaitEachGesture
                                        }

                                        val lassoRect = viewModel.lassoSelectionRect.value
                                        val isDraggingLasso = tool == "LASSO" && lassoRect != null && lassoRect.contains(downEvent.position)
                                        
                                        var previousPosition = downEvent.position
                                        
                                        if (!isDraggingLasso) {
                                            val strokePoint = Point(
                                                x = downEvent.position.x,
                                                y = downEvent.position.y,
                                                pressure = if (pressureSensitivityEnabled) downEvent.pressure else 1.0f
                                            )
                                            currentPoints.add(strokePoint)
                                            if (tool == "ERASER") {
                                                eraserCursorPosition = downEvent.position
                                            }
                                        }
                                        downEvent.consume()

                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.size > 1) {
                                                if (!isDraggingLasso) currentPoints.clear()
                                                eraserCursorPosition = null
                                                break
                                            }

                                            val change = event.changes.firstOrNull { it.id == downEvent.id } ?: break
                                            if (change.pressed) {
                                                if (isDraggingLasso) {
                                                    val deltaX = change.position.x - previousPosition.x
                                                    val deltaY = change.position.y - previousPosition.y
                                                    viewModel.translateLassoSelection(deltaX, deltaY)
                                                    previousPosition = change.position
                                                } else {
                                                    currentPoints.add(Point(change.position.x, change.position.y, if (pressureSensitivityEnabled) change.pressure else 1.0f))
                                                    if (tool == "ERASER") {
                                                        eraserCursorPosition = change.position
                                                        val threshold = thickness * 3.0f
                                                        val currentStrokesList = viewModel.activeStrokes.value
                                                        val cp = change.position
                                                        val toKeep = currentStrokesList.filterNot { existing ->
                                                            existing.points.any { pt ->
                                                                val dx = pt.x - cp.x
                                                                val dy = pt.y - cp.y
                                                                (dx * dx + dy * dy) < (threshold * threshold)
                                                            }
                                                        }
                                                        if (toKeep.size != currentStrokesList.size) {
                                                            viewModel.updateStrokesLive(toKeep)
                                                        }
                                                    }
                                                }
                                                change.consume()
                                            } else {
                                                // Touch released
                                                eraserCursorPosition = null
                                                if (!isDraggingLasso && currentPoints.isNotEmpty()) {
                                                    if (tool == "ERASER") {
                                                        viewModel.saveCurrentPageSnapshot()
                                                    } else if (tool == "LASSO") {
                                                        // Lasso Selection: Enclose items
                                                        if (currentPoints.size > 2) {
                                                            val minX = currentPoints.minOf { it.x }
                                                            val maxX = currentPoints.maxOf { it.x }
                                                            val minY = currentPoints.minOf { it.y }
                                                            val maxY = currentPoints.maxOf { it.y }
                                                            
                                                            val selStrokes = viewModel.activeStrokes.value.filter { st ->
                                                                st.points.any { pt -> pt.x in minX..maxX && pt.y in minY..maxY }
                                                            }
                                                            val selAttachments = viewModel.activeAttachments.value.filter { att ->
                                                                att.x in minX..maxX && att.y in minY..maxY
                                                            }
                                                            
                                                            if (selStrokes.isNotEmpty() || selAttachments.isNotEmpty()) {
                                                                viewModel.lassoSelectedStrokes.value = selStrokes
                                                                viewModel.lassoSelectedAttachments.value = selAttachments
                                                                viewModel.lassoSelectionRect.value = androidx.compose.ui.geometry.Rect(minX, minY, maxX, maxY)
                                                            } else {
                                                                viewModel.clearLassoSelection()
                                                            }
                                                        } else {
                                                            viewModel.clearLassoSelection()
                                                        }
                                                    } else {
                                                        // Standard multi-style brush stroke drawing
                                                        val finalToolName = if (tool == "PEN") activePenSubTool else tool
                                                        
                                                        // Apply Stroke Stabilization (Bezier interpolation approximation)
                                                        val finalPoints = if (strokeStabilization > 0.1f && currentPoints.size > 2) {
                                                            var smoothedPoints = currentPoints.toList()
                                                            val iterations = (strokeStabilization / 2).toInt().coerceAtLeast(1)
                                                            for (i in 0 until iterations) {
                                                                val next = mutableListOf<Point>()
                                                                next.add(smoothedPoints.first())
                                                                for (j in 1 until smoothedPoints.size - 1) {
                                                                    val p0 = smoothedPoints[j - 1]
                                                                    val p1 = smoothedPoints[j]
                                                                    val p2 = smoothedPoints[j + 1]
                                                                    val avgX = (p0.x + p1.x * 2 + p2.x) / 4f // Weighted moving average
                                                                    val avgY = (p0.y + p1.y * 2 + p2.y) / 4f
                                                                    val avgP = (p0.pressure + p1.pressure * 2 + p2.pressure) / 4f
                                                                    next.add(Point(avgX, avgY, avgP))
                                                                }
                                                                next.add(smoothedPoints.last())
                                                                smoothedPoints = next
                                                            }
                                                            smoothedPoints
                                                        } else {
                                                            currentPoints.toList()
                                                        }

                                                        viewModel.addStroke(
                                                            Stroke(
                                                                points = finalPoints,
                                                                color = colorInt,
                                                                thickness = thickness,
                                                                tool = finalToolName,
                                                                shape = shape
                                                            )
                                                        )
                                                    }
                                                    currentPoints.clear()
                                                }
                                                if (isDraggingLasso) {
                                                    viewModel.saveCurrentPageSnapshot()
                                                }
                                                break
                                            }
                                        }
                                    }
                                }
                            }
                    ) {
                        // 0. Paper Guidelines Layer (Bottom)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            clipRect {
                                val style = activeNoteEntity.paperStyle
                                val gridPaint = Color(gridColor)
                                val space = paperGridSpacing
                                if (style == "RULED") {
                                    val countY = (size.height / space).toInt()
                                    for (i in 1..countY) {
                                        drawLine(
                                            color = gridPaint,
                                            start = Offset(0f, i * space),
                                            end = Offset(size.width, i * space),
                                            strokeWidth = ruleGridThickness
                                        )
                                    }
                                } else if (style == "GRAPH") {
                                    val countY = (size.height / space).toInt()
                                    for (i in 1..countY) {
                                        drawLine(
                                            color = gridPaint,
                                            start = Offset(0f, i * space),
                                            end = Offset(size.width, i * space),
                                            strokeWidth = graphGridThickness
                                        )
                                    }
                                    val countX = (size.width / space).toInt()
                                    for (i in 1..countX) {
                                        drawLine(
                                            color = gridPaint,
                                            start = Offset(i * space, 0f),
                                            end = Offset(i * space, size.height),
                                            strokeWidth = graphGridThickness
                                        )
                                    }
                                } else if (style == "DOT") {
                                    val countY = (size.height / space).toInt()
                                    val countX = (size.width / space).toInt()
                                    for (i in 1..countY) {
                                        for (j in 1..countX) {
                                            drawCircle(
                                                color = gridPaint,
                                            radius = dotGridThickness,
                                                center = Offset(j * space, i * space)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 2. Interactive Drawing Board (Strokes) & 3. Draggable attachments
                        // We interleave attachments and grouped strokes based on timestamp z-ordering
                        val renderItems = remember(currentStrokes, currentAttachments) {
                            val items = (currentStrokes + currentAttachments).sortedBy {
                                if (it is Stroke) it.timestamp else (it as Attachment).timestamp
                            }
                            val clusters = mutableListOf<Any>()
                            var currentChunk = mutableListOf<Stroke>()
                            
                            for (item in items) {
                                if (item is Stroke) {
                                    currentChunk.add(item)
                                } else {
                                    if (currentChunk.isNotEmpty()) {
                                        clusters.add(currentChunk.toList())
                                        currentChunk = mutableListOf()
                                    }
                                    clusters.add(item)
                                }
                            }
                            if (currentChunk.isNotEmpty()) {
                                clusters.add(currentChunk.toList())
                            }
                            clusters
                        }

                        renderItems.forEach { cluster ->
                            if (cluster is List<*>) {
                                @Suppress("UNCHECKED_CAST")
                                val chunkStrokes = cluster as List<Stroke>
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    clipRect {
                                        // Render Highlighter Strokes inside chunk Layer
                                        chunkStrokes.filter { it.tool == "HIGHLIGHTER" || it.tool == "BRUSH_HIGHLIGHTER" }.forEach { stroke ->
                                            drawSpecialStroke(stroke)
                                        }
                                        // Render Solid Pen and Shape Overlays on Top Layer within chunk
                                        chunkStrokes.filter { it.tool != "HIGHLIGHTER" && it.tool != "BRUSH_HIGHLIGHTER" }.forEach { stroke ->
                                            drawSpecialStroke(stroke)
                                        }
                                    }
                                }
                            } else if (cluster is Attachment) {
                                val attachment = cluster
                                DraggableAttachmentCard(
                                    attachment = attachment,
                                    isEditorMode = !modeDrawOrPan, // Drag and resize only available in Pan/Nav mode
                                    isSelected = (attachment.id == selectedAttachmentId),
                                    onSelect = {
                                        if (attachment.type == "IMAGE" || attachment.type == "PDF_PAGE") {
                                            selectedAttachmentId = attachment.id
                                        }
                                    },
                                    onUpdate = { attachment -> viewModel.updateAttachment(attachment) },
                                    onDelete = {
                                        if (selectedAttachmentId == attachment.id) {
                                            selectedAttachmentId = null
                                        }
                                        viewModel.removeAttachment(attachment.id)
                                    }
                                )
                            }
                        }

                        // 4. Render active tools and traces on top of everything
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            clipRect {
                                // Render active highlighter (Drawing line in process)
                                if ((tool == "HIGHLIGHTER" || (tool == "PEN" && activePenSubTool == "BRUSH_HIGHLIGHTER")) && currentPoints.size > 1) {
                                    val finalToolName = if (tool == "PEN") activePenSubTool else tool
                                    drawSpecialStroke(
                                        Stroke(
                                            points = currentPoints.toList(),
                                            color = colorInt,
                                            thickness = thickness,
                                            tool = finalToolName,
                                            shape = shape
                                        )
                                    )
                                }

                                // Render temporary selected highlight bounds for scribble deletion confirm overlay
                                temporaryHighlightedStrokes.forEach { stroke ->
                                    if (stroke.points.size > 1) {
                                        val path = Path()
                                        val first = stroke.points.first()
                                        path.moveTo(first.x, first.y)
                                        for (i in 1 until stroke.points.size) {
                                            path.lineTo(stroke.points[i].x, stroke.points[i].y)
                                        }
                                        drawPath(
                                            path = path,
                                            color = Color.Red.copy(alpha = 0.45f),
                                            style = CanvasStroke(
                                                width = stroke.thickness + 12f,
                                                cap = StrokeCap.Round,
                                                join = StrokeJoin.Round
                                            )
                                        )
                                    }
                                }

                                if (tool == "ERASER" && eraserCursorPosition != null) {
                                    val cursorRadius = thickness * 1.5f // Smaller and more precise radius for eraser preview
                                    drawCircle(
                                        color = Color.DarkGray,
                                        radius = cursorRadius,
                                        center = eraserCursorPosition!!,
                                        style = CanvasStroke(
                                            width = 1.5.dp.toPx(),
                                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                        )
                                    )
                                    drawCircle(
                                        color = Color.LightGray.copy(alpha = 0.3f),
                                        radius = cursorRadius,
                                        center = eraserCursorPosition!!
                                    )
                                }

                                // Render active point trace (Drawing line in process)
                                if (tool != "HIGHLIGHTER" && tool != "LASSO" && (tool != "PEN" || activePenSubTool != "BRUSH_HIGHLIGHTER") && currentPoints.size > 1) {
                                    val finalToolName = if (tool == "PEN") activePenSubTool else tool
                                    drawSpecialStroke(
                                        Stroke(
                                            points = currentPoints.toList(),
                                            color = colorInt,
                                            thickness = thickness,
                                            tool = finalToolName,
                                            shape = shape
                                        )
                                    )
                                }

                                // Render Lasso selection dotted boundary loop during dragging selection path
                                if (tool == "LASSO" && currentPoints.size > 1) {
                                    val path = Path()
                                    path.moveTo(currentPoints.first().x, currentPoints.first().y)
                                    for (i in 1 until currentPoints.size) {
                                        path.lineTo(currentPoints[i].x, currentPoints[i].y)
                                    }
                                    drawPath(
                                        path = path,
                                        color = Color(0xFF9C27B0),
                                        style = CanvasStroke(
                                            width = 1.5.dp.toPx(),
                                            cap = StrokeCap.Round,
                                            join = StrokeJoin.Round,
                                            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                                        )
                                    )
                                }
                            }
                        } // Closes Active Interactive Tools Canvas
                        
                    } // Closes Box 636 (white paper bounds)
                } // Closes Box 625 (Scaled inner page)
            } // Closes BoxWithConstraints 1069
                
            // Render active attachment custom border card above edit controls
            val selectedAttachment = currentAttachments.find { it.id == selectedAttachmentId }
            if (selectedAttachment != null && (selectedAttachment.type == "IMAGE" || selectedAttachment.type == "PDF_PAGE") && !modeDrawOrPan && !isReadingMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                ) {
                    ImageBorderCustomizationToolbar(
                        selectedAttachment = selectedAttachment,
                        onUpdate = { viewModel.updateAttachment(it) },
                        onDismiss = { selectedAttachmentId = null }
                    )
                }
            }
            
            // Unified Full Screen Floating Button Setup
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 16.dp, top = 16.dp),
                contentAlignment = Alignment.TopEnd
            ) {
                IconButton(
                    onClick = { viewModel.isFullScreen.value = !isFullScreen },
                    modifier = Modifier
                        .background(if (isFullScreen) Color.Black.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape)
                        .size(44.dp)
                ) {
                    Icon(
                        imageVector = if (isFullScreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = "Toggle Fullscreen",
                        tint = if (isFullScreen) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary
                    )
                }
                        // Bottom Content Overlays (Side Panel & Bottom Bar)
            if (!isFullScreen && !isReadingMode) {
                // Canvas Settings Side Panel
                AnimatedVisibility(
                    visible = showMoreUtilities,
                    enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(0.5f)
                            .padding(top = 70.dp, bottom = 20.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 12.dp,
                        shadowElevation = 16.dp,
                        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Canvas Settings", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { showMoreUtilities = false }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close Settings")
                                }
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.1f))

                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Stylus Only Mode", fontSize = 13.sp)
                                    Switch(
                                        checked = stylusOnly,
                                        onCheckedChange = { viewModel.stylusOnlyMode.value = it },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("A4 Portrait Layout", fontSize = 13.sp)
                                    Switch(
                                        checked = !isLandscape,
                                        onCheckedChange = { viewModel.toggleOrientation() },
                                        modifier = Modifier.scale(0.8f)
                                    )
                                }
                            }

                            if (activeNoteEntity.paperStyle != "BLANK") {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Grid Appearance", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    
                                    Text("Line Color", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    val gridColors = listOf(
                                        Color(0xFFE0E0E0), Color(0xFFBDBDBD), Color(0xFF9E9E9E),
                                        Color(0xFF2E7D32).copy(alpha = 0.3f), Color(0xFF1565C0).copy(alpha = 0.3f),
                                        Color(0xFF5D4037).copy(alpha = 0.3f), Color(0xFFFFCCBC).copy(alpha = 0.6f),
                                        Color(0xFF000000).copy(alpha = 0.15f)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                        gridColors.forEach { c ->
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(c)
                                                    .border(
                                                        width = if (gridColor == c.toArgb()) 2.dp else 1.dp,
                                                        color = if (gridColor == c.toArgb()) MaterialTheme.colorScheme.primary else Color.Transparent,
                                                        shape = CircleShape
                                                    )
                                                    .clickable { viewModel.gridColor.value = c.toArgb() }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Spacing: ${paperGridSpacing.toInt()} dp", fontSize = 11.sp)
                                    Slider(
                                        value = paperGridSpacing,
                                        onValueChange = { viewModel.paperGridSpacing.value = it },
                                        valueRange = 10f..200f,
                                        modifier = Modifier.height(32.dp)
                                    )

                                    when(activeNoteEntity.paperStyle) {
                                        "RULED" -> Column {
                                            Text("Line Thickness: ${"%.1f".format(ruleGridThickness)}", fontSize = 11.sp)
                                            Slider(value = ruleGridThickness, onValueChange = { viewModel.ruleGridThickness.value = it }, valueRange = 0.1f..5f, modifier = Modifier.height(32.dp))
                                        }
                                        "GRAPH" -> Column {
                                            Text("Graph Thickness: ${"%.1f".format(graphGridThickness)}", fontSize = 11.sp)
                                            Slider(value = graphGridThickness, onValueChange = { viewModel.graphGridThickness.value = it }, valueRange = 0.1f..5f, modifier = Modifier.height(32.dp))
                                        }
                                        "DOT" -> Column {
                                            Text("Dot Radius: ${"%.1f".format(dotGridThickness)}", fontSize = 11.sp)
                                            Slider(value = dotGridThickness, onValueChange = { viewModel.dotGridThickness.value = it }, valueRange = 0.5f..8f, modifier = Modifier.height(32.dp))
                                        }
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Page Background", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    IconButton(onClick = { colorPickerTarget = "PAGE_BACKGROUND" }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.ColorLens, contentDescription = "Custom", modifier = Modifier.size(16.dp))
                                    }
                                }
                                val bgPresets = listOf(Color(0xFFFFFFFF), Color(0xFFF5F5F5), Color(0xFFFFFDE7), Color(0xFFE8F5E9), Color(0xFFE3F2FD), Color(0xFFFCE4EC))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                    bgPresets.forEach { c ->
                                        Box(
                                            modifier = Modifier.size(28.dp).clip(CircleShape).background(c)
                                                .border(if (noteBackgroundColor == c.toArgb()) 2.dp else 1.dp, if (noteBackgroundColor == c.toArgb()) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f), CircleShape)
                                                .clickable { viewModel.noteBackgroundColor.value = c.toArgb() }
                                        )
                                    }
                                }
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Canvas Workspace Color", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                val wsPresets = listOf(Color(0xFFE0E0E0), Color(0xFFF5F5F5), Color(0xFF263238), Color(0xFF1A1A1A), Color(0xFF37474F), Color(0xFF424242))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                    wsPresets.forEach { c ->
                                        Box(
                                            modifier = Modifier.size(28.dp).clip(CircleShape).background(c)
                                                .border(if (backgroundColor == c.toArgb()) 2.dp else 1.dp, if (backgroundColor == c.toArgb()) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.3f), CircleShape)
                                                .clickable { viewModel.canvasBackgroundColor.value = c.toArgb() }
                                        )
                                    }
                                }
                            }

                            Column {
                                Text("Stroke Stabilization", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Slider(value = strokeStabilization, onValueChange = { viewModel.strokeStabilization.value = it }, valueRange = 0f..10f, modifier = Modifier.height(32.dp))
                            }
                        }
                    }
                }

                // Space-saving Floating Markup Bar & Overlays (Redesigned for minimum space)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier.wrapContentSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Sleek Floating Tool Options & Sub-menus Overlay Card
                        AnimatedVisibility(
                            visible = showDrawingSettingsPanel && !isFullScreen && !isReadingMode,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 500.dp)
                                    .shadow(8.dp, RoundedCornerShape(16.dp)),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Brush Options",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        IconButton(
                                            onClick = { showDrawingSettingsPanel = false },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Close Settings", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    
                                    val subTools = when(tool) {
                                        "PEN", "PENCIL" -> listOf("Ballpoint", "Fountain", "Calligraphy", "Pencil", "Brush", "Neon")
                                        "SHAPE" -> listOf("Rectangle", "Circle", "Line", "Arrow", "Triangle")
                                        else -> emptyList()
                                    }
                                    if (subTools.isNotEmpty()) {
                                        LazyRow(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            items(subTools) { subTool ->
                                                val subToolId = subTool.uppercase().replace(" ", "_")
                                                val isSelected = when(tool) {
                                                    "PEN", "PENCIL" -> if (subToolId == "PENCIL") tool == "PENCIL" else tool == "PEN" && activePenSubTool == "PEN_$subToolId"
                                                    "SHAPE" -> tool == "SHAPE" && shape == subToolId
                                                    else -> false
                                                }
                                                FilterChip(
                                                    selected = isSelected,
                                                    onClick = {
                                                        if ((tool == "PEN" || tool == "PENCIL") && subToolId == "PENCIL") {
                                                            viewModel.setToolClassAndRestore("PENCIL")
                                                            viewModel.activePenSubTool.value = "PENCIL_STANDARD"
                                                        } else if (tool == "PEN" || tool == "PENCIL") {
                                                            viewModel.setToolClassAndRestore("PEN")
                                                            viewModel.activePenSubTool.value = "PEN_$subToolId"
                                                        } else if (tool == "SHAPE") {
                                                            viewModel.setToolClassAndRestore("SHAPE")
                                                            viewModel.activeShape.value = subToolId
                                                        }
                                                    },
                                                    label = { Text(subTool, fontSize = 10.sp) },
                                                    colors = FilterChipDefaults.filterChipColors(
                                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                                                    ),
                                                    modifier = Modifier.height(28.dp)
                                                )
                                            }
                                        }
                                    }

                                    if (tool == "PEN" || tool == "PENCIL") {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Gesture,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "Pressure Sensitivity",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                            Switch(
                                                checked = pressureSensitivityEnabled,
                                                onCheckedChange = { viewModel.pressureSensitivityEnabled.value = it },
                                                modifier = Modifier.height(24.dp)
                                            )
                                        }
                                    }
                                    
                                    ToolSettingsPanel(
                                        thickness = thickness,
                                        onThicknessChange = { thick -> viewModel.updateActiveThickness(thick) },
                                        color = Color(colorInt),
                                        onColorChange = { col -> viewModel.updateActiveColor(col.toArgb()) },
                                        onMoreColors = { colorPickerTarget = "TOOL" },
                                        activeToolName = toolForPicker,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                        
                        // 2. Drag-to-sort Page Navigation Centre Overlay Card
                        AnimatedVisibility(
                            visible = showPageNavigationCentre && !isFullScreen && !isReadingMode,
                            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                        ) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 500.dp)
                                    .shadow(12.dp, RoundedCornerShape(20.dp)),
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(20.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Icon(Icons.Default.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                            Text("Page Sorter Hub", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                                                Text("${notePages.size} pg", color = MaterialTheme.colorScheme.onPrimaryContainer, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 4.dp))
                                            }
                                        }
                                        IconButton(
                                            onClick = { showPageNavigationCentre = false },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Close Pages Center", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    
                                    Text(
                                        text = "Long press & drag card left/right to swap pages. Tap to focused view.",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                    
                                    Box(modifier = Modifier.fillMaxWidth().height(140.dp)) {
                                        val localPages = remember(notePages) { mutableStateListOf<com.example.data.database.NotePageEntity>().apply { addAll(notePages) } }
                                        var draggedIndex by remember { mutableStateOf<Int?>(null) }
                                        var dragOffsetX by remember { mutableStateOf(0f) }
                                        
                                        LaunchedEffect(notePages) {
                                            if (draggedIndex == null) {
                                                localPages.clear()
                                                localPages.addAll(notePages)
                                            }
                                        }
                                        
                                        val sidebarDensity = androidx.compose.ui.platform.LocalDensity.current
                                        val curDensity = sidebarDensity.density
                                        val cardWidth = 90.dp
                                        val spacing = 8.dp
                                        val stepPx = with(sidebarDensity) { (cardWidth + spacing).toPx() }
                                        
                                        LazyRow(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalArrangement = Arrangement.spacedBy(spacing),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                                        ) {
                                            items(
                                                count = localPages.size,
                                                key = { idx -> localPages[idx].id }
                                            ) { index ->
                                                val pageEntity = localPages[index]
                                                val isSelected = pageEntity.pageIndex == currentPageIndex
                                                val isDragged = index == draggedIndex
                                                val currentIndexState = androidx.compose.runtime.rememberUpdatedState(index)
                                                
                                                val translationXState = animateFloatAsState(
                                                    targetValue = if (isDragged) dragOffsetX else 0f,
                                                    animationSpec = if (isDragged) snap() else spring(stiffness = Spring.StiffnessMedium),
                                                    label = "PageDrag"
                                                )
                                                
                                                val strokesList = remember(pageEntity.drawingDataJson) {
                                                    try {
                                                        val jArray = JSONArray(pageEntity.drawingDataJson)
                                                        val sList = mutableListOf<Stroke>()
                                                        for (i in 0 until jArray.length()) {
                                                            sList.add(Stroke.fromJson(jArray.getJSONObject(i)))
                                                        }
                                                        sList
                                                    } catch (e: Exception) {
                                                        emptyList()
                                                    }
                                                }
                                                val attachmentsList = remember(pageEntity.attachmentsJson) {
                                                    try {
                                                        Attachment.listFromJson(pageEntity.attachmentsJson)
                                                    } catch (e: Exception) {
                                                        emptyList()
                                                    }
                                                }
                                                
                                                Box(
                                                    modifier = Modifier
                                                        .width(cardWidth)
                                                        .fillMaxHeight()
                                                        .graphicsLayer {
                                                            translationX = translationXState.value
                                                            scaleX = if (isDragged) 1.06f else 1.0f
                                                            scaleY = if (isDragged) 1.06f else 1.0f
                                                            shadowElevation = if (isDragged) 8.dp.toPx() else 1.dp.toPx()
                                                        }
                                                        .pointerInput(pageEntity.id) {
                                                            detectDragGesturesAfterLongPress(
                                                                onDragStart = {
                                                                    draggedIndex = currentIndexState.value
                                                                    dragOffsetX = 0f
                                                                },
                                                                onDrag = { change, dragAmount ->
                                                                    change.consume()
                                                                    dragOffsetX += dragAmount.x
                                                                    
                                                                    val curIdx = draggedIndex ?: return@detectDragGesturesAfterLongPress
                                                                    if (dragOffsetX > stepPx / 2f && curIdx < localPages.size - 1) {
                                                                        val updatedPages = localPages.toMutableList()
                                                                        val temp = updatedPages[curIdx]
                                                                        updatedPages[curIdx] = updatedPages[curIdx + 1]
                                                                        updatedPages[curIdx + 1] = temp
                                                                        
                                                                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                                                                            localPages.clear()
                                                                            localPages.addAll(updatedPages)
                                                                        }
                                                                        draggedIndex = curIdx + 1
                                                                        dragOffsetX -= stepPx
                                                                    } else if (dragOffsetX < -stepPx / 2f && curIdx > 0) {
                                                                        val updatedPages = localPages.toMutableList()
                                                                        val temp = updatedPages[curIdx]
                                                                        updatedPages[curIdx] = updatedPages[curIdx - 1]
                                                                        updatedPages[curIdx - 1] = temp
                                                                        
                                                                        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot {
                                                                            localPages.clear()
                                                                            localPages.addAll(updatedPages)
                                                                        }
                                                                        draggedIndex = curIdx - 1
                                                                        dragOffsetX += stepPx
                                                                    }
                                                                },
                                                                onDragEnd = {
                                                                    draggedIndex = null
                                                                    dragOffsetX = 0f
                                                                    viewModel.reorderPages(localPages.toList())
                                                                },
                                                                onDragCancel = {
                                                                    draggedIndex = null
                                                                    dragOffsetX = 0f
                                                                    localPages.clear()
                                                                    localPages.addAll(notePages)
                                                                }
                                                            )
                                                        }
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                                        .border(
                                                            width = if (isSelected) 2.5.dp else 1.dp,
                                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.4f),
                                                            shape = RoundedCornerShape(8.dp)
                                                        )
                                                        .clickable {
                                                            viewModel.changePage(pageEntity.pageIndex)
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
                                                        val isLandscape = activeNoteEntity.isLandscape
                                                        val targetWidthPx = (if (isLandscape) 1754f else 1240f) * curDensity
                                                        val targetHeightPx = (if (isLandscape) 1240f else 1754f) * curDensity
                                                        val scaleX = size.width / targetWidthPx
                                                        val scaleY = size.height / targetHeightPx
                                                        val scale = minOf(scaleX, scaleY)
                                                        withTransform({
                                                            scale(scale, scale, Offset.Zero)
                                                        }) {
                                                            strokesList.forEach { stroke ->
                                                                drawSpecialStroke(stroke)
                                                            }
                                                            attachmentsList.forEach { attachment ->
                                                                val wPx = attachment.width * curDensity
                                                                val hPx = attachment.height * curDensity
                                                                drawRect(
                                                                    color = Color.LightGray.copy(alpha = 0.6f),
                                                                    topLeft = Offset(attachment.x, attachment.y),
                                                                    size = androidx.compose.ui.geometry.Size(wPx, hPx),
                                                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f / scale)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    
                                                    Box(
                                                        modifier = Modifier
                                                            .align(Alignment.BottomCenter)
                                                            .fillMaxWidth()
                                                            .background(if (isSelected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f))
                                                            .padding(vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "${pageEntity.pageIndex + 1}",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                            textAlign = TextAlign.Center,
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    }
                                                    
                                                    IconButton(
                                                        onClick = {
                                                            viewModel.changePage(pageEntity.pageIndex)
                                                            viewModel.deleteCurrentPage()
                                                        },
                                                        modifier = Modifier
                                                            .align(Alignment.TopEnd)
                                                            .size(20.dp)
                                                            .background(Color.Black.copy(alpha = 0.2f), CircleShape)
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(10.dp))
                                                    }
                                                }
                                            }
                                            
                                            item {
                                                Surface(
                                                    modifier = Modifier
                                                        .width(90.dp)
                                                        .fillMaxHeight()
                                                        .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                                        .clickable { viewModel.addPage() },
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
                                                ) {
                                                    Column(
                                                        modifier = Modifier.fillMaxSize(),
                                                        horizontalAlignment = Alignment.CenterHorizontally,
                                                        verticalArrangement = Arrangement.Center
                                                    ) {
                                                        Icon(Icons.Default.AddCircle, contentDescription = "Add Page", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                                                        Spacer(modifier = Modifier.height(4.dp))
                                                        Text("Add Page", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        
                        // 3. Compact Floating Capsule Markup Bar
                        Surface(
                            tonalElevation = 12.dp,
                            shadowElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier
                                .wrapContentWidth()
                                .height(50.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Pages shortcut modal selector
                                IconButton(
                                    onClick = {
                                        showPageNavigationCentre = !showPageNavigationCentre
                                        showDrawingSettingsPanel = false
                                    },
                                    modifier = Modifier
                                        .background(if (showPageNavigationCentre) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, CircleShape)
                                        .height(34.dp)
                                        .wrapContentWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.MenuBook, 
                                            contentDescription = "Page Menu", 
                                            modifier = Modifier.size(16.dp),
                                            tint = if (showPageNavigationCentre) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "${currentPageIndex + 1}/${notePages.size}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (showPageNavigationCentre) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                        Icon(
                                            Icons.Default.ArrowDropDown, 
                                            contentDescription = null, 
                                            modifier = Modifier.size(14.dp),
                                            tint = if (showPageNavigationCentre) MaterialTheme.colorScheme.onPrimaryContainer else Color.Gray
                                        )
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(2.dp))
                                VerticalDivider(modifier = Modifier.height(20.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                
                                val mainTools = listOf(
                                    "PEN" to Icons.Default.Gesture,
                                    "HIGHLIGHTER" to Icons.Default.Highlight,
                                    "SHAPE" to Icons.Default.Category,
                                    "ERASER" to Icons.Default.CropPortrait,
                                    "IMAGE" to Icons.Default.AddPhotoAlternate,
                                    "PDF" to Icons.Default.PictureAsPdf
                                )
                                
                                mainTools.forEach { (toolId, icon) ->
                                    val isActive = tool == toolId
                                    IconButton(
                                        onClick = {
                                            if (toolId == "IMAGE") {
                                                directImageLauncher.launch("image/*")
                                            } else if (toolId == "PDF") {
                                                directPdfLauncher.launch("application/pdf")
                                            } else {
                                                viewModel.setToolClassAndRestore(toolId)
                                                if (toolId == "PEN" || toolId == "SHAPE" || toolId == "HIGHLIGHTER") {
                                                    showDrawingSettingsPanel = true
                                                    showPageNavigationCentre = false
                                                } else {
                                                    showDrawingSettingsPanel = false
                                                    showPageNavigationCentre = false
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(36.dp),
                                        colors = IconButtonDefaults.iconButtonColors(
                                            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                            contentColor = if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                        )
                                    ) {
                                        Icon(icon, contentDescription = toolId, modifier = Modifier.size(18.dp))
                                    }
                                }
                                
                                Spacer(modifier = Modifier.width(2.dp))
                                VerticalDivider(modifier = Modifier.height(20.dp))
                                Spacer(modifier = Modifier.width(2.dp))
                                
                                // Expand brush options toggle
                                IconButton(
                                    onClick = {
                                        showDrawingSettingsPanel = !showDrawingSettingsPanel
                                        showPageNavigationCentre = false
                                    },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = if (showDrawingSettingsPanel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                        contentColor = if (showDrawingSettingsPanel) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Icon(Icons.Default.Tune, contentDescription = "Brush Settings", modifier = Modifier.size(18.dp))
                                }
                                
                                // Extra settings toggle (opens more utilities panel)
                                IconButton(
                                    onClick = { showMoreUtilities = !showMoreUtilities },
                                    modifier = Modifier.size(36.dp),
                                    colors = IconButtonDefaults.iconButtonColors(
                                        containerColor = if (showMoreUtilities) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent,
                                        contentColor = if (showMoreUtilities) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface
                                    )
                                ) {
                                    Icon(Icons.Default.Settings, contentDescription = "Dashboard Tools Settings", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

    if (colorPickerTarget != null) {
        val isTool = colorPickerTarget == "TOOL"
        ColorPickerSheet(
            title = if (isTool) "Select ${toolForPicker.lowercase().replaceFirstChar { it.uppercase() }} Color" else "Select Page Color",
            colorInt = if (isTool) colorInt else noteBackgroundColor,
            onColorSelected = { newColor ->
                if (isTool) {
                    viewModel.updateActiveColor(newColor)
                } else {
                    viewModel.noteBackgroundColor.value = newColor
                }
            },
            currentThickness = if (isTool) thickness else null,
            onThicknessSelected = if (isTool) { { newThick -> viewModel.updateActiveThickness(newThick) } } else null,
            onDismiss = { colorPickerTarget = null }
        )
    }



    if (showPagesGridDialog) {
        PagesGridOverviewDialog(
            pages = notePages,
            currentPageIndex = currentPageIndex,
            onSelectPage = { index ->
                viewModel.changePage(index)
                showPagesGridDialog = false
            },
            onDismiss = { showPagesGridDialog = false },
            viewModel = viewModel
        )
    }
}
