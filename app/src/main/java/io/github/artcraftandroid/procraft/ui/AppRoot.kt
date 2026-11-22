package io.github.artcraftandroid.procraft.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.canvas.PaintingCanvas
import io.github.artcraftandroid.procraft.canvas.Screen
import io.github.artcraftandroid.procraft.canvas.hasActiveSelectionKeyframe
import io.github.artcraftandroid.procraft.canvas.importAudioTrack
import io.github.artcraftandroid.procraft.canvas.importImageTrack
import io.github.artcraftandroid.procraft.canvas.importVideoTrack
import io.github.artcraftandroid.procraft.model.Panel

@Composable
fun AppRoot(vm: CanvasViewModel) {
    val importAbrPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importAbrBrushes(uri)
    }
    val launchImportAbr = {
        importAbrPicker.launch(arrayOf("*/*"))
    }

    AnimatedContent(
        targetState = vm.screen,
        transitionSpec = { fadeIn(Motion.fade()) togetherWith fadeOut(Motion.fade()) },
        label = "root_screen",
    ) { screen ->
        when (screen) {
            Screen.GALLERY -> GalleryScreen(vm)
            Screen.CANVAS -> CanvasWorkspace(vm, onImportAbr = launchImportAbr)
        }
    }

    if (vm.customCanvasOpen) {
        CustomCanvasDialog(
            editing = vm.editingPresetIndex?.let { vm.presets.getOrNull(it) },
            onDismiss = {
                vm.customCanvasOpen = false
                vm.editingPresetIndex = null
            },
            onSaveAndCreate = { preset ->
                vm.saveCustomPreset(preset, vm.editingPresetIndex)
                vm.customCanvasOpen = false
                vm.editingPresetIndex = null
                vm.newArtworkFromPreset(preset)
            },
        )
    }

    vm.brushStudioTarget?.let { target ->
        BrushStudioModal(
            preset = target,
            colorRgb = vm.colorRgb,
            fakePressureEnabled = vm.gestureSettings.fakePressureEnabled,
            onUpdate = { updated -> vm.updateBrushPreset(updated) },
            onDeleteCustom = { vm.deleteCustomBrush(target.id) },
            onImportAbr = launchImportAbr,
            onClose = { vm.brushStudioTarget = null },
        )
    }

    if (vm.gestureControlsOpen) {
        GestureControlsDialog(vm = vm, onDismiss = { vm.gestureControlsOpen = false })
    }

    if (vm.canvasInfoOpen) {
        CanvasInfoDialog(vm = vm, onDismiss = { vm.canvasInfoOpen = false })
    }

    if (vm.timelapseReplayOpen) {
        TimeLapseReplayDialog(vm = vm, onDismiss = { vm.timelapseReplayOpen = false })
    }

    if (vm.animatedExportOpen) {
        AnimatedExportDialog(vm = vm, onDismiss = { vm.animatedExportOpen = false })
    }

    if (vm.transformNumericOpen) {
        TransformNumericDialog(vm = vm, onDismiss = { vm.transformNumericOpen = false })
    }

    if (vm.shortcutsHelpOpen) {
        ShortcutsHelpDialog(onDismiss = { vm.shortcutsHelpOpen = false })
    }

    if (vm.inBetweenDialogOpen) {
        InBetweenDialog(vm = vm, onDismiss = { vm.inBetweenDialogOpen = false })
    }
}

@Composable
private fun CanvasWorkspace(
    vm: CanvasViewModel,
    onImportAbr: () -> Unit,
) {
    val insertPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) vm.insertPhotoAsLayer(uri)
    }
    val refPhotoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) vm.pickReferenceImage(uri)
    }
    val importVideoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) vm.importVideoTrack(uri)
    }
    val importAudioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importAudioTrack(uri)
    }
    val importImagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) vm.importImageTrack(uri)
    }

    LaunchedEffect(vm.selecting, vm.transforming, vm.animationState.isAnimation) {
        if ((vm.selecting || vm.transforming) && vm.hasActiveSelectionKeyframe()) {
            vm.selectionCurveOverlayOpen = true
        } else if (!vm.selecting && !vm.transforming) {
            vm.selectionCurveOverlayOpen = false
        }
    }

    BackHandler {
        when {
            vm.copyPasteMenuOpen -> vm.copyPasteMenuOpen = false
            vm.animatedExportOpen -> vm.animatedExportOpen = false
            vm.transformNumericOpen -> vm.transformNumericOpen = false
            vm.shortcutsHelpOpen -> vm.shortcutsHelpOpen = false
            vm.inBetweenDialogOpen -> vm.inBetweenDialogOpen = false
            vm.layerOrderPickerGroupId != null -> vm.layerOrderPickerGroupId = null
            vm.selectionCurveOverlayOpen -> vm.selectionCurveOverlayOpen = false
            vm.quickMenuCenter != null -> vm.quickMenuCenter = null
            vm.panel != Panel.NONE -> vm.panel = Panel.NONE
            vm.adjustingKind != null -> vm.cancelAdjustment()
            vm.transforming -> vm.commitTransform()
            vm.selecting -> {
                vm.anchorMoveMode = false
                vm.selecting = false
            }
            vm.cropping -> vm.cropping = false
            vm.editingGuide -> vm.editingGuide = false
            vm.textEditing -> vm.textEditing = false
            vm.fullscreen -> vm.fullscreen = false
            else -> vm.closeArtwork()
        }
    }

    ClearBandShows(Pc.Bar)
    Box(
        Modifier
            .fillMaxSize()
            .background(Pc.Background),
    ) {
        Column(Modifier.fillMaxSize()) {
            AnimatedVisibility(
                visible = !vm.fullscreen,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
            ) {
                TopBar(vm)
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                PaintingCanvas(vm, Modifier.fillMaxSize())

                if (!vm.fullscreen) {
                    val alignSide = if (vm.gestureSettings.rightHanded) Alignment.CenterEnd else Alignment.CenterStart
                    SideBar(
                        vm = vm,
                        right = vm.gestureSettings.rightHanded,
                        modifier = Modifier.align(alignSide),
                    )
                } else {
                    IconButton(
                        onClick = { vm.fullscreen = false },
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp)
                            .size(48.dp)
                            .testTag("exit_fullscreen_button"),
                    ) {
                        Icon(PcIcons.FullScreen, contentDescription = "Exit Full Screen", tint = Pc.BarGlyph)
                    }
                }

                // Dismiss panel scrim
                if (vm.panel != Panel.NONE) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(vm.panel) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        if (ev.changes.any { it.pressed }) {
                                            vm.panel = Panel.NONE
                                        }
                                    }
                                }
                            },
                    )
                }

                // Floating Popover Panels anchored under TopBar buttons
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 2.dp),
                ) {
                    val outerPad = 10.dp
                    val fit = Pc.barFit(maxWidth + outerPad * 2)
                    val actionsCenter = fit.margin + Pc.GalleryWidth + fit.actionGap + fit.button * 0.5f - outerPad
                    val adjustmentsCenter = fit.margin + Pc.GalleryWidth + fit.actionGap * 2f + fit.button * 1.5f - outerPad
                    val colorCenterFromEnd = fit.margin + fit.button * 0.5f - outerPad
                    val layersCenterFromEnd = fit.margin + fit.button * 1.5f + fit.toolGap - outerPad
                    val brushToolCenterFromEnd = when (vm.tool) {
                        io.github.artcraftandroid.procraft.model.ToolKind.PAINT ->
                            fit.margin + fit.button * 4.5f + fit.toolGap * 4f - outerPad
                        io.github.artcraftandroid.procraft.model.ToolKind.SMUDGE ->
                            fit.margin + fit.button * 3.5f + fit.toolGap * 3f - outerPad
                        io.github.artcraftandroid.procraft.model.ToolKind.ERASE ->
                            fit.margin + fit.button * 2.5f + fit.toolGap * 2f - outerPad
                    }

                    if (vm.panel == Panel.ACTIONS) {
                        ActionsPopover(
                            vm = vm,
                            onInsertPhoto = {
                                vm.panel = Panel.NONE
                                insertPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onPickReference = {
                                vm.panel = Panel.NONE
                                refPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            caretFromStart = actionsCenter.coerceAtLeast(18.dp),
                            modifier = Modifier.align(Alignment.TopStart),
                        )
                    }
                    if (vm.panel == Panel.ADJUSTMENTS) {
                        AdjustmentsPopover(
                            vm = vm,
                            caretFromStart = (adjustmentsCenter - 18.dp).coerceAtLeast(18.dp),
                            modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp),
                        )
                    }
                    if (vm.panel == Panel.BRUSHES) {
                        BrushLibraryPopover(
                            vm = vm,
                            caretFromEnd = (brushToolCenterFromEnd - 12.dp).coerceAtLeast(18.dp),
                            onImportAbr = onImportAbr,
                            modifier = Modifier.align(Alignment.TopEnd).padding(end = 12.dp),
                        )
                    }
                    if (vm.panel == Panel.LAYERS) {
                        LayersPopover(
                            vm = vm,
                            caretFromEnd = layersCenterFromEnd.coerceAtLeast(18.dp),
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                    if (vm.panel == Panel.COLOR) {
                        ColorPanelPopover(
                            vm = vm,
                            caretFromEnd = colorCenterFromEnd.coerceAtLeast(18.dp),
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                }

                // Bottom Tool Bars (Adjustment / Selection / Transform / Crop / Guide / Text / Page Assist)
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (vm.animationState.isAnimation &&
                        (vm.transforming || vm.selecting) &&
                        vm.selectionCurveOverlayOpen
                    ) {
                        SelectionCurveOverlayPanel(vm)
                    }
                    when {
                        vm.adjustingKind != null -> AdjustmentControlBar(vm)
                        vm.transforming -> TransformControlBar(vm)
                        vm.selecting -> SelectionControlBar(vm)
                        vm.cropping -> CropControlBar(vm)
                        vm.editingGuide -> GuideControlBar(vm)
                        vm.textEditing -> TextEditorBar(vm)
                        vm.animationState.isPageAssist -> PageAssistBar(vm)
                    }
                }

                // Docked Multi-Track Animation & Video-Editor Timeline
                if (vm.animationState.isAnimation &&
                    vm.adjustingKind == null &&
                    !vm.transforming &&
                    !vm.selecting &&
                    !vm.cropping &&
                    !vm.editingGuide &&
                    !vm.textEditing &&
                    !vm.animationState.isPageAssist
                ) {
                    AnimationTimelineDock(
                        vm = vm,
                        onImportVideo = {
                            importVideoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                            )
                        },
                        onImportAudio = {
                            importAudioPicker.launch(arrayOf("audio/*"))
                        },
                        onImportImage = {
                            importImagePicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }

                // Live Zoom HUD Pill
                if (vm.gestureSettings.zoomHudEnabled && !vm.fullscreen && !vm.animationState.isAnimation) {
                    ZoomHudPill(
                        vm = vm,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp),
                    )
                }

                // Floating Companion Preview Window
                if (vm.companionVisible) {
                    CompanionWindowOverlay(
                        vm = vm,
                        modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp, start = 16.dp),
                    )
                }

                // Floating Reference Window
                if (vm.referenceVisible) {
                    ReferenceWindowOverlay(
                        vm = vm,
                        onPickImage = {
                            refPhotoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 16.dp),
                    )
                }

                // Copy & Paste Popover
                if (vm.copyPasteMenuOpen) {
                    CopyPastePopover(vm = vm, onDismiss = { vm.copyPasteMenuOpen = false })
                }

                // Radial QuickMenu
                vm.quickMenuCenter?.let { center ->
                    RadialQuickMenu(vm = vm, centerPx = center, onDismiss = { vm.quickMenuCenter = null })
                }

                // Eyedropper magnifying loupe
                vm.pickLoupe?.let { loupe ->
                    EyedropperLoupe(loupe)
                }

                // Floating Notice Pill
                vm.noticeText?.let { msg ->
                    Box(
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .background(Color(0xE628282C), Pc.PillShape)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(msg, color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        if (vm.loadingArtwork) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).clickable(enabled = false) {},
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Pc.Accent)
            }
        }
    }
}
