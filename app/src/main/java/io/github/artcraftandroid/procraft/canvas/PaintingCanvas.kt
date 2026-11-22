package io.github.artcraftandroid.procraft.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import io.github.artcraftandroid.procraft.engine.StrokePoint
import io.github.artcraftandroid.procraft.model.*
import io.github.artcraftandroid.procraft.ui.Pc
import io.github.artcraftandroid.procraft.ui.screenAreaToCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PaintingCanvas(vm: CanvasViewModel, modifier: Modifier = Modifier) {
    val art = vm.currentArtwork ?: return
    val selVer = vm.selectionVersion
    val selOverlayBitmap = remember(selVer, vm.selectionOverlay) { vm.selectionOverlay?.asImageBitmap() }
    val tilePaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = false
            isFilterBitmap = true
        }
    }

    val scope = rememberCoroutineScope()
    var activeCursorPos by remember { mutableStateOf<Offset?>(null) }
    var freehandPoints by remember { mutableStateOf<List<Pair<Float, Float>>>(emptyList()) }
    var shapeAnchor by remember { mutableStateOf<Offset?>(null) }
    var shapeEnd by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier
            .fillMaxSize()
            .background(Pc.Background)
            .clipToBounds()
            .onSizeChanged { s ->
                val newSize = Size(s.width.toFloat(), s.height.toFloat())
                val firstLayout = vm.canvasAreaSize.width == 1080f && vm.canvasAreaSize.height == 1920f
                vm.canvasAreaSize = newSize
                if (firstLayout) vm.fitCanvasToScreen()
            }
            .pointerInput(
                art.id,
                vm.selecting,
                vm.selectionKind,
                vm.transforming,
                vm.anchorMoveMode,
                vm.cropping,
                vm.pickNext,
                vm.adjustingKind,
                vm.gestureSettings,
            ) {
                awaitPointerEventScope {
                    var multiTouchActive = false
                    var maxPointersInGesture = 0
                    var gestureStartMs = 0L
                    var totalFingerMove = 0f
                    var threeFingerStartPan = Offset.Zero
                    var threeFingerLastPan = Offset.Zero
                    var threeFingerTracking = false
                    var prevPan = Offset.Zero
                    var prevDist = 0f
                    var prevAngle = 0f
                    var drawingActive = false
                    var activeTransformHandle = -1
                    var draggingAnchor = false
                    var draggingRotation = false
                    var prevRotAngleDeg = 0f
                    var selectionStartedEmpty = false
                    var prevTransformCanvasPos = Offset.Zero
                    var prevStrokeCanvasPos = Offset.Zero
                    var lastQuickShapeCheckPos = Offset.Zero
                    var lastMoveMs = 0L
                    var quickShapeJob: kotlinx.coroutines.Job? = null
                    var quickShapeTriggered = false
                    var eyedropperJob: kotlinx.coroutines.Job? = null
                    var eyedropperActive = false
                    val pointBatch = ArrayList<StrokePoint>(16)

                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        val pressed = changes.filter { it.pressed }

                        if (pressed.isEmpty()) {
                            eyedropperJob?.cancel()
                            quickShapeJob?.cancel()
                            if (eyedropperActive) {
                                vm.pickLoupe = null
                                eyedropperActive = false
                            } else if (drawingActive) {
                                vm.endStroke()
                                drawingActive = false
                            } else if (vm.selecting && !multiTouchActive && !draggingAnchor) {
                                // Finish selection shape
                                val a = shapeAnchor
                                val b = shapeEnd
                                if (vm.selectionKind == SelectionKind.FREEHAND && freehandPoints.size >= 3) {
                                    vm.selectionMask?.addPolygon(freehandPoints, vm.selectionSubtract)
                                    vm.updateSelectionOverlay()
                                } else if (a != null && b != null) {
                                    when (vm.selectionKind) {
                                        SelectionKind.RECTANGLE -> vm.selectionMask?.addRect(a.x, a.y, b.x, b.y, vm.selectionSubtract)
                                        SelectionKind.ELLIPSE -> vm.selectionMask?.addEllipse(a.x, a.y, b.x, b.y, vm.selectionSubtract)
                                        else -> Unit
                                    }
                                    vm.updateSelectionOverlay()
                                }
                                if (selectionStartedEmpty && vm.selectionMask?.isEmpty() == false) {
                                    vm.recomputeDefaultSelectionAnchor()
                                }
                                selectionStartedEmpty = false
                                freehandPoints = emptyList()
                                shapeAnchor = null
                                shapeEnd = null
                            } else if (multiTouchActive) {
                                val elapsed = System.currentTimeMillis() - gestureStartMs
                                val dy3 = threeFingerLastPan.y - threeFingerStartPan.y
                                val dx3 = threeFingerLastPan.x - threeFingerStartPan.x
                                if (elapsed < 340L && totalFingerMove < 36f) {
                                    when (maxPointersInGesture) {
                                        2 -> if (vm.gestureSettings.twoFingerUndo) vm.undo()
                                        3 -> if (vm.gestureSettings.threeFingerRedo) vm.redo()
                                        4 -> if (vm.gestureSettings.fourFingerFullscreen) vm.fullscreen = !vm.fullscreen
                                    }
                                } else if (maxPointersInGesture == 3 && threeFingerTracking && dy3 > 110f && abs(dy3) > abs(dx3) * 1.35f && totalFingerMove < 260f && vm.gestureSettings.threeFingerCopyPaste) {
                                    vm.copyPasteMenuOpen = true
                                } else if (maxPointersInGesture == 3 && totalFingerMove > 260f && vm.gestureSettings.threeFingerClear) {
                                    vm.clearLayer(vm.activeLayerIndex)
                                }
                            }
                            multiTouchActive = false
                            threeFingerTracking = false
                            activeTransformHandle = -1
                            draggingAnchor = false
                            draggingRotation = false
                            maxPointersInGesture = 0
                            totalFingerMove = 0f
                            activeCursorPos = null
                            continue
                        }

                        if (maxPointersInGesture == 0) {
                            gestureStartMs = System.currentTimeMillis()
                        }
                        if (pressed.size > maxPointersInGesture) {
                            maxPointersInGesture = pressed.size
                        }

                        if (pressed.size >= 2) {
                            // Cancel any started stroke when 2+ fingers touch (clean Undo + Viewport gesture)
                            eyedropperJob?.cancel()
                            quickShapeJob?.cancel()
                            if (drawingActive) {
                                vm.endStroke()
                                vm.undo()
                                drawingActive = false
                            }
                            if (pressed.size >= 3) {
                                var sx = 0f
                                var sy = 0f
                                pressed.forEach { sx += it.position.x; sy += it.position.y }
                                val centroid = Offset(sx / pressed.size, sy / pressed.size)
                                if (!threeFingerTracking) {
                                    threeFingerTracking = true
                                    threeFingerStartPan = centroid
                                    threeFingerLastPan = centroid
                                } else {
                                    val d = centroid - threeFingerLastPan
                                    totalFingerMove += hypot(d.x, d.y)
                                    threeFingerLastPan = centroid
                                }
                                multiTouchActive = true
                                pressed.forEach { it.consume() }
                                continue
                            }
                            if (!multiTouchActive) {
                                multiTouchActive = true
                                val p0 = pressed[0].position
                                val p1 = pressed[1].position
                                prevPan = (p0 + p1) * 0.5f
                                prevDist = hypot(p1.x - p0.x, p1.y - p0.y).coerceAtLeast(1f)
                                prevAngle = atan2(p1.y - p0.y, p1.x - p0.x)
                            } else {
                                val p0 = pressed[0].position
                                val p1 = pressed[1].position
                                val pan = (p0 + p1) * 0.5f
                                val dist = hypot(p1.x - p0.x, p1.y - p0.y).coerceAtLeast(1f)
                                val angle = atan2(p1.y - p0.y, p1.x - p0.x)

                                val panDelta = pan - prevPan
                                totalFingerMove += hypot(panDelta.x, panDelta.y)
                                vm.viewPan += panDelta

                                val scaleFactor = dist / prevDist
                                vm.viewZoom = (vm.viewZoom * scaleFactor).coerceIn(0.1f, 16f)

                                var dAngle = Math.toDegrees((angle - prevAngle).toDouble()).toFloat()
                                if (dAngle > 180f) dAngle -= 360f
                                if (dAngle < -180f) dAngle += 360f
                                vm.viewRotationDeg += dAngle

                                prevPan = pan
                                prevDist = dist
                                prevAngle = angle
                            }
                            pressed.forEach { it.consume() }
                            continue
                        }

                        if (multiTouchActive) {
                            pressed.forEach { it.consume() }
                            continue
                        }

                        val ch = pressed.first()
                        if (vm.gestureSettings.palmRejection && ch.type == PointerType.Touch) {
                            continue
                        }

                        val screenPos = ch.position
                        activeCursorPos = screenPos
                        val canvasPos = screenAreaToCanvas(vm, screenPos)
                        val isDownEdge = !ch.previousPressed && ch.pressed

                        if (vm.pickNext) {
                            if (isDownEdge) {
                                vm.sampleColorAt(canvasPos)?.let { picked ->
                                    vm.setColor(picked, recordHistory = true)
                                    vm.showNotice("Color picked")
                                }
                                vm.pickNext = false
                            }
                            ch.consume()
                            continue
                        }

                        if (vm.transforming) {
                            val cur = vm.transformState
                            val wf = art.width.toFloat()
                            val hf = art.height.toFloat()
                            val anchorPt = cur.worldAnchor(wf, hf)
                            val anchorPos = Offset(anchorPt.x, anchorPt.y)
                            val rotHandle = computeTransformRotationHandle(cur, wf, hf, vm.viewZoom)
                            if (isDownEdge) {
                                prevTransformCanvasPos = canvasPos
                                val hitThreshold = 42f / vm.viewZoom
                                val anchorDist = hypot(anchorPos.x - canvasPos.x, anchorPos.y - canvasPos.y)
                                val rotDist = rotHandle?.let { hypot(it.x - canvasPos.x, it.y - canvasPos.y) } ?: Float.MAX_VALUE
                                if (vm.anchorMoveMode || anchorDist < hitThreshold) {
                                    draggingAnchor = true
                                    if (vm.anchorMoveMode) vm.repositionTransformAnchor(canvasPos)
                                } else if (rotDist < hitThreshold) {
                                    draggingRotation = true
                                    prevRotAngleDeg = Math.toDegrees(
                                        atan2((canvasPos.y - anchorPos.y).toDouble(), (canvasPos.x - anchorPos.x).toDouble()),
                                    ).toFloat()
                                } else {
                                    val handles = computeTransformHandlePoints(cur, wf, hf)
                                    var bestIdx = -1
                                    var bestDist = 38f / vm.viewZoom
                                    handles.forEachIndexed { idx, pt ->
                                        val d = hypot(pt.x - canvasPos.x, pt.y - canvasPos.y)
                                        if (d < bestDist) {
                                            bestDist = d
                                            bestIdx = idx
                                        }
                                    }
                                    activeTransformHandle = bestIdx
                                }
                            } else if (draggingAnchor) {
                                vm.repositionTransformAnchor(canvasPos)
                                prevTransformCanvasPos = canvasPos
                            } else if (draggingRotation) {
                                val curAngle = Math.toDegrees(
                                    atan2((canvasPos.y - anchorPos.y).toDouble(), (canvasPos.x - anchorPos.x).toDouble()),
                                ).toFloat()
                                var dA = curAngle - prevRotAngleDeg
                                if (dA > 180f) dA -= 360f
                                if (dA < -180f) dA += 360f
                                prevRotAngleDeg = curAngle
                                vm.previewTransform(cur.copy(rotationDeg = cur.rotationDeg + dA))
                                prevTransformCanvasPos = canvasPos
                            } else {
                                val cDelta = canvasPos - prevTransformCanvasPos
                                if (activeTransformHandle == -1) {
                                    vm.previewTransform(cur.copy(translateX = cur.translateX + cDelta.x, translateY = cur.translateY + cDelta.y))
                                } else {
                                    val rad = -Math.toRadians(cur.rotationDeg.toDouble())
                                    val cosR = cos(rad).toFloat()
                                    val sinR = sin(rad).toFloat()
                                    val rx = cDelta.x * cosR - cDelta.y * sinR
                                    val ry = cDelta.x * sinR + cDelta.y * cosR
                                    val sxInv = if (cur.flipH) -1f / cur.scaleX.coerceAtLeast(0.05f) else 1f / cur.scaleX.coerceAtLeast(0.05f)
                                    val syInv = if (cur.flipV) -1f / cur.scaleY.coerceAtLeast(0.05f) else 1f / cur.scaleY.coerceAtLeast(0.05f)
                                    val localDx = rx * sxInv
                                    val localDy = ry * syInv
                                    when (cur.kind) {
                                        TransformKind.WARP -> {
                                            val updated = cur.warpOffsets.toMutableList()
                                            val old = updated[activeTransformHandle]
                                            updated[activeTransformHandle] = (old.first + localDx) to (old.second + localDy)
                                            vm.previewTransform(cur.copy(warpOffsets = updated))
                                        }
                                        TransformKind.DISTORT -> {
                                            val updated = cur.cornerOffsets.toMutableList()
                                            val old = updated[activeTransformHandle]
                                            updated[activeTransformHandle] = (old.first + localDx) to (old.second + localDy)
                                            vm.previewTransform(cur.copy(cornerOffsets = updated))
                                        }
                                        TransformKind.UNIFORM -> {
                                            val cx = anchorPos.x
                                            val cy = anchorPos.y
                                            val oldD = hypot(prevTransformCanvasPos.x - cx, prevTransformCanvasPos.y - cy).coerceAtLeast(10f)
                                            val newD = hypot(canvasPos.x - cx, canvasPos.y - cy).coerceAtLeast(10f)
                                            val factor = newD / oldD
                                            val ns = (cur.scaleX * factor).coerceIn(0.15f, 4f)
                                            vm.previewTransform(cur.copy(scaleX = ns, scaleY = ns))
                                        }
                                        TransformKind.FREEFORM -> {
                                            val cx = anchorPos.x
                                            val cy = anchorPos.y
                                            val oldDx = abs(prevTransformCanvasPos.x - cx).coerceAtLeast(10f)
                                            val newDx = abs(canvasPos.x - cx).coerceAtLeast(10f)
                                            val oldDy = abs(prevTransformCanvasPos.y - cy).coerceAtLeast(10f)
                                            val newDy = abs(canvasPos.y - cy).coerceAtLeast(10f)
                                            vm.previewTransform(
                                                cur.copy(
                                                    scaleX = (cur.scaleX * (newDx / oldDx)).coerceIn(0.15f, 4f),
                                                    scaleY = (cur.scaleY * (newDy / oldDy)).coerceIn(0.15f, 4f),
                                                ),
                                            )
                                        }
                                    }
                                }
                                prevTransformCanvasPos = canvasPos
                            }
                            ch.consume()
                            continue
                        }

                        if (vm.selecting) {
                            if (isDownEdge) {
                                val anchorPos = vm.selectionAnchor
                                val hitAnchor = vm.anchorMoveMode ||
                                    (anchorPos != null && hypot(anchorPos.x - canvasPos.x, anchorPos.y - canvasPos.y) < 42f / vm.viewZoom)
                                if (hitAnchor) {
                                    draggingAnchor = true
                                    vm.moveSelectionAnchor(canvasPos)
                                } else {
                                    selectionStartedEmpty = vm.selectionMask?.isEmpty() != false
                                    if (vm.selectionKind == SelectionKind.AUTOMATIC) {
                                        val targetBmp = vm.activeLayer()?.bitmap
                                        if (targetBmp != null) {
                                            vm.selectionMask?.addFloodSelect(
                                                targetBmp,
                                                canvasPos.x.roundToInt(),
                                                canvasPos.y.roundToInt(),
                                                0.22f,
                                                vm.selectionSubtract,
                                            )
                                            vm.updateSelectionOverlay()
                                            if (selectionStartedEmpty && vm.selectionMask?.isEmpty() == false) {
                                                vm.recomputeDefaultSelectionAnchor()
                                            }
                                        }
                                    } else {
                                        shapeAnchor = canvasPos
                                        shapeEnd = canvasPos
                                        freehandPoints = listOf(canvasPos.x to canvasPos.y)
                                    }
                                }
                            } else if (draggingAnchor) {
                                vm.moveSelectionAnchor(canvasPos)
                            } else {
                                shapeEnd = canvasPos
                                if (vm.selectionKind == SelectionKind.FREEHAND) {
                                    freehandPoints = freehandPoints + (canvasPos.x to canvasPos.y)
                                }
                            }
                            ch.consume()
                            continue
                        }

                        if (vm.cropping) {
                            val curCrop = vm.cropState
                            if (curCrop != null) {
                                val delta = screenPos - ch.previousPosition
                                val dx = (delta.x / vm.viewZoom).roundToInt()
                                val dy = (delta.y / vm.viewZoom).roundToInt()
                                val nw = (curCrop.right - curCrop.left)
                                val nh = (curCrop.bottom - curCrop.top)
                                val nl = (curCrop.left + dx).coerceIn(0, (art.width - 64).coerceAtLeast(0))
                                val nt = (curCrop.top + dy).coerceIn(0, (art.height - 64).coerceAtLeast(0))
                                vm.cropState = CropState(nl, nt, (nl + nw).coerceAtMost(art.width), (nt + nh).coerceAtMost(art.height))
                            }
                            ch.consume()
                            continue
                        }

                        // Standard Painting / Smudging / Erasing / Liquify / Clone
                        val pressure = if (ch.type == PointerType.Stylus) ch.pressure.coerceIn(0.05f, 1f) else 0.85f
                        if (isDownEdge) {
                            drawingActive = true
                            quickShapeTriggered = false
                            prevStrokeCanvasPos = canvasPos
                            lastQuickShapeCheckPos = canvasPos
                            lastMoveMs = System.currentTimeMillis()
                            vm.startStroke(canvasPos, pressure, 0f)

                            // Eyedropper touch & hold timer
                            eyedropperJob?.cancel()
                            eyedropperJob = scope.launch {
                                delay(vm.gestureSettings.eyedropperHoldMs.toLong())
                                if (drawingActive && hypot(canvasPos.x - prevStrokeCanvasPos.x, canvasPos.y - prevStrokeCanvasPos.y) < 12f) {
                                    eyedropperActive = true
                                    drawingActive = false
                                    quickShapeJob?.cancel()
                                    vm.endStroke()
                                    vm.undo()
                                    val c = vm.sampleColorAt(canvasPos) ?: vm.colorRgb
                                    vm.pickLoupe = PickLoupeState(screenPos, c, vm.colorRgb)
                                    vm.setColor(c, recordHistory = false)
                                }
                            }

                            // Single QuickShape watchdog coroutine per stroke (zero per-move allocations)
                            quickShapeJob?.cancel()
                            quickShapeJob = scope.launch {
                                while (drawingActive && !quickShapeTriggered) {
                                    delay(120)
                                    if (drawingActive && !quickShapeTriggered && System.currentTimeMillis() - lastMoveMs >= 480) {
                                        quickShapeTriggered = true
                                        vm.triggerQuickShapeIfMatched()
                                    }
                                }
                            }
                        } else if (eyedropperActive) {
                            val c = vm.sampleColorAt(canvasPos) ?: vm.colorRgb
                            vm.pickLoupe = PickLoupeState(screenPos, c, vm.prevColorRgb)
                            vm.setColor(c, recordHistory = true)
                        } else if (drawingActive) {
                            if (hypot(canvasPos.x - lastQuickShapeCheckPos.x, canvasPos.y - lastQuickShapeCheckPos.y) > 8f) {
                                eyedropperJob?.cancel()
                                lastQuickShapeCheckPos = canvasPos
                                lastMoveMs = System.currentTimeMillis()
                                quickShapeTriggered = false
                            }
                            pointBatch.clear()
                            val hist = ch.historical
                            for (i in hist.indices) {
                                val hp = screenAreaToCanvas(vm, hist[i].position)
                                pointBatch.add(StrokePoint(hp.x, hp.y, pressure, 0f, hist[i].uptimeMillis))
                            }
                            pointBatch.add(StrokePoint(canvasPos.x, canvasPos.y, pressure, 0f, ch.uptimeMillis))
                            vm.moveStrokeBatch(pointBatch, prevStrokeCanvasPos)
                            prevStrokeCanvasPos = canvasPos
                        }
                        ch.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.5f + vm.viewPan.x, size.height * 0.5f + vm.viewPan.y)
            withTransform({
                translate(center.x, center.y)
                rotate(vm.viewRotationDeg, pivot = Offset.Zero)
                scale(vm.viewZoom, vm.viewZoom, pivot = Offset.Zero)
                translate(-art.width * 0.5f, -art.height * 0.5f)
            }) {
                // Transparent canvas checkerboard if background is hidden
                if (!vm.backgroundVisible) {
                    drawRect(Color(0xFFCCCCCC), size = Size(art.width.toFloat(), art.height.toFloat()))
                }
                val cache = vm.tileCache
                if (cache != null) {
                    tilePaint.isFilterBitmap = vm.viewZoom < 2f
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        val tiles = cache.tiles
                        for (i in tiles.indices) {
                            val tile = tiles[i]
                            // Reading tile.version inside DrawScope invalidates only drawing, zero recomposition
                            if (tile.version >= 0 && !tile.bitmap.isRecycled) {
                                native.drawBitmap(tile.bitmap, tile.left.toFloat(), tile.top.toFloat(), tilePaint)
                            }
                        }
                    }
                }

                // Selection mask overlay
                if (selOverlayBitmap != null) {
                    drawImage(selOverlayBitmap)
                }

                // Live freehand / rectangle / ellipse selection outline + Movable Anchor
                if (vm.selecting) {
                    val a = shapeAnchor
                    val b = shapeEnd
                    if (vm.selectionKind == SelectionKind.FREEHAND && freehandPoints.size >= 2) {
                        val p = Path().apply {
                            moveTo(freehandPoints.first().first, freehandPoints.first().second)
                            for (i in 1 until freehandPoints.size) lineTo(freehandPoints[i].first, freehandPoints[i].second)
                        }
                        drawPath(p, Pc.Accent, style = Stroke(width = 3f / vm.viewZoom))
                    } else if (a != null && b != null) {
                        val l = min(a.x, b.x)
                        val t = min(a.y, b.y)
                        val w = abs(b.x - a.x)
                        val h = abs(b.y - a.y)
                        if (vm.selectionKind == SelectionKind.RECTANGLE) {
                            drawRect(Pc.Accent, Offset(l, t), Size(w, h), style = Stroke(width = 3f / vm.viewZoom))
                        } else if (vm.selectionKind == SelectionKind.ELLIPSE) {
                            drawOval(Pc.Accent, Offset(l, t), Size(w, h), style = Stroke(width = 3f / vm.viewZoom))
                        }
                    }
                    vm.selectionAnchor?.let { anchor ->
                        drawAnchorCrosshair(
                            center = anchor,
                            zoom = vm.viewZoom,
                            highlighted = vm.anchorMoveMode,
                        )
                    }
                }

                // Drawing Guides Overlay
                if (vm.guideConfig.enabled) {
                    drawGuideOverlay(vm.guideConfig, art.width.toFloat(), art.height.toFloat(), vm.viewZoom)
                }

                // Transform bounding box, Rotation handle, Movable Anchor & Distort / Warp mesh handles
                if (vm.transforming) {
                    val t = vm.transformState
                    val wf = art.width.toFloat()
                    val hf = art.height.toFloat()
                    val strokeW = 2.5f / vm.viewZoom
                    val hr = 11f / vm.viewZoom
                    val handles = computeTransformHandlePoints(t, wf, hf)
                    if (t.kind == TransformKind.WARP && handles.size == 9) {
                        for (r in 0..2) {
                            drawLine(Pc.Accent, handles[r * 3], handles[r * 3 + 1], strokeWidth = strokeW * 0.85f)
                            drawLine(Pc.Accent, handles[r * 3 + 1], handles[r * 3 + 2], strokeWidth = strokeW * 0.85f)
                        }
                        for (c in 0..2) {
                            drawLine(Pc.Accent, handles[c], handles[3 + c], strokeWidth = strokeW * 0.85f)
                            drawLine(Pc.Accent, handles[3 + c], handles[6 + c], strokeWidth = strokeW * 0.85f)
                        }
                    } else if (handles.size == 4) {
                        val poly = Path().apply {
                            moveTo(handles[0].x, handles[0].y)
                            lineTo(handles[1].x, handles[1].y)
                            lineTo(handles[2].x, handles[2].y)
                            lineTo(handles[3].x, handles[3].y)
                            close()
                        }
                        drawPath(poly, Pc.Accent, style = Stroke(strokeW))
                        val topMid = (handles[0] + handles[1]) * 0.5f
                        val rotHandle = computeTransformRotationHandle(t, wf, hf, vm.viewZoom)
                        if (rotHandle != null) {
                            drawLine(Pc.Accent, topMid, rotHandle, strokeWidth = strokeW)
                            drawCircle(Color(0xFF30D158), hr * 1.15f, rotHandle)
                            drawCircle(Color.White, hr * 1.15f, rotHandle, style = Stroke(strokeW))
                        }
                    }
                    handles.forEach { corner ->
                        drawCircle(Color.White, hr, corner)
                        drawCircle(Pc.Accent, hr, corner, style = Stroke(strokeW))
                    }
                    val wp = t.worldAnchor(wf, hf)
                    drawAnchorCrosshair(
                        center = Offset(wp.x, wp.y),
                        zoom = vm.viewZoom,
                        highlighted = vm.anchorMoveMode,
                    )
                }

                // Crop Overlay
                if (vm.cropping) {
                    val c = vm.cropState
                    if (c != null) {
                        drawRect(
                            Pc.Accent,
                            Offset(c.left.toFloat(), c.top.toFloat()),
                            Size((c.right - c.left).toFloat(), (c.bottom - c.top).toFloat()),
                            style = Stroke(3.5f / vm.viewZoom),
                        )
                    }
                }
            }

            // Active brush cursor ring / Liquify puck / ColorDrop droplet
            if (vm.gestureSettings.brushCursor && activeCursorPos != null && !vm.selecting && !vm.transforming) {
                val minDim = min(art.width, art.height).toFloat().coerceAtLeast(256f)
                val rPx = (2f + vm.brushSize.pow(1.6f) * minDim * 0.14f) * vm.viewZoom
                drawCircle(Color(0x99FFFFFF), rPx.coerceIn(6f, 200f), activeCursorPos!!, style = Stroke(1.5f))
                drawCircle(Color(0x66000000), (rPx - 1.5f).coerceIn(4f, 198f), activeCursorPos!!, style = Stroke(1f))
            }

            vm.colorDroppingPos?.let { dropPos ->
                drawCircle(Color(vm.colorRgb), 26f, dropPos)
                drawCircle(Color.White, 26f, dropPos, style = Stroke(3f))
            }
        }
    }
}

private fun DrawScope.drawGuideOverlay(guide: GuideConfig, w: Float, h: Float, zoom: Float) {
    val col = Color(guide.colorRgb).copy(alpha = guide.opacity)
    val sw = (guide.thickness / zoom).coerceAtLeast(0.8f)
    val spacing = guide.gridSpacingPx.coerceIn(24f, 400f)
    when (guide.kind) {
        GuideKind.GRID_2D -> {
            var x = spacing
            while (x < w) {
                drawLine(col, Offset(x, 0f), Offset(x, h), strokeWidth = sw)
                x += spacing
            }
            var y = spacing
            while (y < h) {
                drawLine(col, Offset(0f, y), Offset(w, y), strokeWidth = sw)
                y += spacing
            }
        }
        GuideKind.ISOMETRIC -> {
            val tan30 = 0.57735f
            var offset = -h
            while (offset < w + h) {
                drawLine(col, Offset(offset, 0f), Offset(offset + h / tan30, h), strokeWidth = sw)
                drawLine(col, Offset(offset, h), Offset(offset + h / tan30, 0f), strokeWidth = sw)
                offset += spacing
            }
        }
        GuideKind.PERSPECTIVE -> {
            guide.perspectivePoints.forEach { pt ->
                val vp = Offset(pt.x * w, pt.y * h)
                drawCircle(col, 14f / zoom, vp)
                for (k in 0 until 12) {
                    val a = k * (2f * PI.toFloat() / 12f)
                    drawLine(col, vp, Offset(vp.x + cos(a) * w * 2f, vp.y + sin(a) * h * 2f), strokeWidth = sw)
                }
            }
        }
        GuideKind.SYMMETRY_VERTICAL -> {
            drawLine(col, Offset(w * 0.5f, 0f), Offset(w * 0.5f, h), strokeWidth = sw * 1.6f)
        }
        GuideKind.SYMMETRY_HORIZONTAL -> {
            drawLine(col, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = sw * 1.6f)
        }
        GuideKind.SYMMETRY_QUADRANT -> {
            drawLine(col, Offset(w * 0.5f, 0f), Offset(w * 0.5f, h), strokeWidth = sw * 1.6f)
            drawLine(col, Offset(0f, h * 0.5f), Offset(w, h * 0.5f), strokeWidth = sw * 1.6f)
        }
        GuideKind.SYMMETRY_RADIAL -> {
            val cx = w * 0.5f
            val cy = h * 0.5f
            val segs = guide.radialSegments.coerceIn(2, 16)
            val rad = hypot(w, h)
            for (i in 0 until segs) {
                val a = (2f * PI.toFloat() * i / segs)
                drawLine(col, Offset(cx, cy), Offset(cx + cos(a) * rad, cy + sin(a) * rad), strokeWidth = sw * 1.4f)
            }
        }
    }
}

private fun DrawScope.drawAnchorCrosshair(
    center: Offset,
    zoom: Float,
    highlighted: Boolean,
) {
    val r = (if (highlighted) 20f else 15f) / zoom
    val cross = r * 1.45f
    val sw = (if (highlighted) 3.2f else 2.4f) / zoom
    val ringColor = if (highlighted) Color(0xFFFFD60A) else Color(0xFFFF9F0A)
    drawCircle(Color(0x66000000), radius = r + sw, center = center)
    drawCircle(ringColor.copy(alpha = 0.25f), radius = r, center = center)
    drawCircle(Color.White, radius = r, center = center, style = Stroke(sw * 1.15f))
    drawCircle(ringColor, radius = r * 0.68f, center = center, style = Stroke(sw))
    drawCircle(ringColor, radius = r * 0.26f, center = center)
    drawLine(Color.White, Offset(center.x - cross, center.y), Offset(center.x - r * 0.45f, center.y), strokeWidth = sw)
    drawLine(Color.White, Offset(center.x + r * 0.45f, center.y), Offset(center.x + cross, center.y), strokeWidth = sw)
    drawLine(Color.White, Offset(center.x, center.y - cross), Offset(center.x, center.y - r * 0.45f), strokeWidth = sw)
    drawLine(Color.White, Offset(center.x, center.y + r * 0.45f), Offset(center.x, center.y + cross), strokeWidth = sw)
}

private fun computeTransformRotationHandle(
    t: io.github.artcraftandroid.procraft.engine.LayerTransformState,
    w: Float,
    h: Float,
    zoom: Float,
): Offset? {
    if (t.kind == TransformKind.WARP) return null
    val corners = computeTransformHandlePoints(t, w, h)
    if (corners.size < 4) return null
    val topMid = (corners[0] + corners[1]) * 0.5f
    val bottomMid = (corners[3] + corners[2]) * 0.5f
    val up = topMid - bottomMid
    val len = hypot(up.x, up.y).coerceAtLeast(1f)
    val stemLen = 46f / zoom
    return Offset(topMid.x + (up.x / len) * stemLen, topMid.y + (up.y / len) * stemLen)
}

private fun computeTransformHandlePoints(
    t: io.github.artcraftandroid.procraft.engine.LayerTransformState,
    w: Float,
    h: Float,
): List<Offset> {
    val b = t.effectiveBounds(w, h)
    val matrix = t.buildMatrix(b.centerX(), b.centerY())
    if (t.kind == TransformKind.WARP) {
        val raw = FloatArray(9 * 2)
        for (r in 0..2) {
            for (c in 0..2) {
                val idx = r * 3 + c
                val off = t.warpOffsets.getOrElse(idx) { 0f to 0f }
                raw[idx * 2] = c * (w * 0.5f) + off.first
                raw[idx * 2 + 1] = r * (h * 0.5f) + off.second
            }
        }
        matrix.mapPoints(raw)
        return List(9) { idx -> Offset(raw[idx * 2], raw[idx * 2 + 1]) }
    }
    val co = if (t.kind == TransformKind.DISTORT) t.cornerOffsets else listOf(0f to 0f, 0f to 0f, 0f to 0f, 0f to 0f)
    val raw = floatArrayOf(
        b.left + co[0].first, b.top + co[0].second,
        b.right + co[1].first, b.top + co[1].second,
        b.right + co[2].first, b.bottom + co[2].second,
        b.left + co[3].first, b.bottom + co[3].second,
    )
    matrix.mapPoints(raw)
    return List(4) { idx -> Offset(raw[idx * 2], raw[idx * 2 + 1]) }
}
