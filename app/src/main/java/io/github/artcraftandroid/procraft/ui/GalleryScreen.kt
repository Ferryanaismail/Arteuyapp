package io.github.artcraftandroid.procraft.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.artcraftandroid.procraft.R
import io.github.artcraftandroid.procraft.canvas.CanvasViewModel
import io.github.artcraftandroid.procraft.model.ArtworkRecord
import io.github.artcraftandroid.procraft.model.GalleryItem
import io.github.artcraftandroid.procraft.model.GallerySortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun GalleryScreen(vm: CanvasViewModel) {
    var selecting by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var newCanvasOpen by remember { mutableStateOf(false) }
    var renamingArt by remember { mutableStateOf<ArtworkRecord?>(null) }
    var renamingStack by remember { mutableStateOf<GalleryItem.StackGroup?>(null) }
    var shareTarget by remember { mutableStateOf<ArtworkRecord?>(null) }
    var deleteConfirm by remember { mutableStateOf<List<String>?>(null) }
    var swipedId by remember { mutableStateOf<String?>(null) }
    var aboutOpen by remember { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.importPictureAsArtwork(uri)
    }

    if (vm.openStackId != null && !selecting) {
        BackHandler { vm.openStack(null) }
    }
    if (selecting) {
        BackHandler {
            selecting = false
            selectedIds.clear()
        }
    }

    ClearBandShows(Pc.GalleryBackground)
    val blurRadius = if (renamingArt != null || renamingStack != null || aboutOpen) 16.dp else 0.dp

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Pc.GalleryBackground),
    ) {
        val columns = when {
            maxWidth >= 900.dp -> 4
            maxWidth >= 600.dp -> 3
            else -> 2
        }
        val stackName = vm.openStackId?.let { sid ->
            vm.artworks.firstOrNull { it.stackId == sid }?.stackName ?: "Stack"
        }

        Column(
            Modifier
                .fillMaxSize()
                .then(if (blurRadius > 0.dp) Modifier.blur(blurRadius) else Modifier),
        ) {
            // Gallery Header
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    if (vm.openStackId != null && !selecting) {
                        IconButton(
                            onClick = { vm.openStack(null) },
                            modifier = Modifier.size(48.dp).testTag("stack_back_button"),
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Gallery", tint = Pc.TextPrimary)
                        }
                    }
                    Text(
                        text = stackName ?: "ProCraft",
                        color = Pc.TextPrimary,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { if (vm.openStackId == null && !selecting) aboutOpen = true },
                    )
                }

                if (!selecting) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Pc.PanelSurface)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        TextButton(
                            onClick = { selecting = true; swipedId = null },
                            modifier = Modifier.testTag("gallery_select_button"),
                        ) {
                            Text("Select", color = Pc.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        TextButton(
                            onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                            modifier = Modifier.testTag("gallery_import_photo"),
                        ) {
                            Text("Photo", color = Pc.TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        }
                        IconButton(
                            onClick = { newCanvasOpen = true; swipedId = null },
                            modifier = Modifier.size(48.dp).testTag("gallery_new_canvas_button"),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "New Canvas", tint = Pc.TextPrimary)
                        }
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Pc.PanelSurface)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        if (selectedIds.size >= 2 && vm.openStackId == null) {
                            TextButton(
                                onClick = {
                                    vm.stackArtworks(selectedIds.toList())
                                    selecting = false
                                    selectedIds.clear()
                                },
                                modifier = Modifier.testTag("gallery_stack_selected"),
                            ) { Text("Stack", color = Pc.Accent) }
                        }
                        if (selectedIds.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    vm.duplicateArtworks(selectedIds.toList())
                                    selecting = false
                                    selectedIds.clear()
                                },
                                modifier = Modifier.testTag("gallery_duplicate_selected"),
                            ) { Text("Duplicate", color = Pc.TextPrimary) }
                            TextButton(
                                onClick = { deleteConfirm = selectedIds.toList() },
                                modifier = Modifier.testTag("gallery_delete_selected"),
                            ) { Text("Delete", color = Pc.Destructive) }
                        }
                        IconButton(
                            onClick = { selecting = false; selectedIds.clear() },
                            modifier = Modifier.size(48.dp).testTag("gallery_close_select"),
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Done Selecting", tint = Pc.TextPrimary)
                        }
                    }
                }
            }

            // Search & Sort Bar
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PcCompactSearchField(
                    value = vm.gallerySearch,
                    onValueChange = { vm.gallerySearch = it },
                    placeholder = "Search artworks...",
                    inputTag = "gallery_search_input",
                    modifier = Modifier.weight(1f),
                )
                Row(
                    Modifier
                        .heightIn(min = 42.dp)
                        .clip(RoundedCornerShape(21.dp))
                        .background(Pc.PanelSurface)
                        .border(0.8.dp, Pc.Divider, RoundedCornerShape(21.dp))
                        .clickable {
                            vm.gallerySort = if (vm.gallerySort == GallerySortMode.RECENT) GallerySortMode.NAME else GallerySortMode.RECENT
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .testTag("gallery_sort_button"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(vm.gallerySort.label, color = Pc.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // Grid of Artworks & Stacks
            val items = vm.galleryItems
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("No Artworks Yet", color = Pc.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                        Button(
                            onClick = { newCanvasOpen = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Pc.Accent),
                        ) { Text("Create Your First Canvas", color = Color.White) }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = {
                        when (it) {
                            is GalleryItem.SingleArtwork -> it.artwork.id
                            is GalleryItem.StackGroup -> "stack_${it.stackId}"
                        }
                    }) { entry ->
                        when (entry) {
                            is GalleryItem.SingleArtwork -> {
                                val art = entry.artwork
                                val isSel = art.id in selectedIds
                                ArtworkCard(
                                    artwork = art,
                                    selecting = selecting,
                                    selected = isSel,
                                    swiped = swipedId == art.id,
                                    inStack = vm.openStackId != null,
                                    onSwipeChanged = { open -> swipedId = if (open) art.id else null },
                                    onTap = {
                                        if (selecting) {
                                            if (isSel) selectedIds.remove(art.id) else selectedIds.add(art.id)
                                        } else {
                                            swipedId = null
                                            vm.openArtwork(art)
                                        }
                                    },
                                    onRename = { renamingArt = art },
                                    onShare = { swipedId = null; shareTarget = art },
                                    onDuplicate = { swipedId = null; vm.duplicateArtworks(listOf(art.id)) },
                                    onDelete = { swipedId = null; deleteConfirm = listOf(art.id) },
                                    onUnstack = { vm.removeArtworkFromStack(art) },
                                )
                            }
                            is GalleryItem.StackGroup -> {
                                val stackArtIds = entry.items.map { it.id }
                                val isSel = stackArtIds.isNotEmpty() && stackArtIds.all { it in selectedIds }
                                StackCard(
                                    stack = entry,
                                    selecting = selecting,
                                    selected = isSel,
                                    onTap = {
                                        if (selecting) {
                                            if (isSel) selectedIds.removeAll(stackArtIds) else selectedIds.addAll(stackArtIds.filter { it !in selectedIds })
                                        } else {
                                            vm.openStack(entry.stackId)
                                        }
                                    },
                                    onRename = { renamingStack = entry },
                                )
                            }
                        }
                    }
                }
            }
        }

        // New Canvas Popover Menu
        if (newCanvasOpen) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x55000000))
                    .clickable { newCanvasOpen = false },
            ) {
                PcPopoverFrame(
                    caretFromEnd = 24.dp,
                    caretColor = Pc.PanelHeader,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 66.dp, end = 16.dp)
                        .width(316.dp)
                        .heightIn(max = 470.dp),
                    bodyModifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Pc.PanelHeader)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("New Canvas", color = Pc.TextPrimary, fontSize = Pc.TitleSize, fontWeight = FontWeight.SemiBold)
                        IconButton(
                            onClick = {
                                newCanvasOpen = false
                                vm.editingPresetIndex = null
                                vm.customCanvasOpen = true
                            },
                            modifier = Modifier.size(48.dp).testTag("add_custom_preset_button"),
                        ) {
                            Icon(PcIcons.MenuNewLayer, contentDescription = "Create Custom Canvas", tint = Pc.Accent, modifier = Modifier.size(22.dp))
                        }
                    }
                    HorizontalDivider(color = Pc.Divider, thickness = 0.6.dp)
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PcSectionCard {
                            vm.presets.forEachIndexed { idx, preset ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            newCanvasOpen = false
                                            vm.newArtworkFromPreset(preset)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(preset.name, color = Pc.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        Text(preset.dimensionLabel, color = Pc.TextSecondary, fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            newCanvasOpen = false
                                            vm.editingPresetIndex = idx
                                            vm.customCanvasOpen = true
                                        },
                                        modifier = Modifier.size(40.dp),
                                    ) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "Edit Preset", tint = Pc.TextSecondary, modifier = Modifier.size(18.dp))
                                    }
                                }
                                if (idx < vm.presets.lastIndex) {
                                    PcRowDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Rename Artwork Dialog
    renamingArt?.let { art ->
        RenameDialog(
            title = "Rename Artwork",
            initial = art.title,
            onDismiss = { renamingArt = null },
            onConfirm = { newName ->
                vm.renameArtwork(art, newName)
                renamingArt = null
            },
        )
    }

    // Rename Stack Dialog
    renamingStack?.let { st ->
        RenameDialog(
            title = "Rename Stack",
            initial = st.name,
            onDismiss = { renamingStack = null },
            onConfirm = { newName ->
                vm.renameStack(st.stackId, newName)
                renamingStack = null
            },
        )
    }

    // Share Format Dialog
    shareTarget?.let { art ->
        PcDialogShell(
            title = "Share ${art.title}",
            onDismiss = { shareTarget = null },
            leftLabel = "Cancel",
            onLeftClick = { shareTarget = null },
            rightLabel = null,
            onRightClick = null,
            modifier = Modifier.widthIn(max = 330.dp).fillMaxWidth(0.86f),
        ) {
            Column(Modifier.padding(12.dp)) {
                PcSectionCard("Format") {
                    val formats = listOf("PNG" to "Lossless image", "JPEG" to "Compressed photo", "PDF" to "Printable document")
                    formats.forEachIndexed { idx, (fmt, desc) ->
                        PcNavRow(
                            label = fmt,
                            icon = LucideIcons.TabShare,
                            trailingText = desc,
                            showChevron = false,
                        ) {
                            shareTarget = null
                            vm.shareArtworkFromGallery(art, fmt)
                        }
                        if (idx < formats.lastIndex) PcRowDivider(indent = 42.dp)
                    }
                }
            }
        }
    }

    // Delete Confirmation Dialog
    deleteConfirm?.let { ids ->
        PcDialogShell(
            title = "Delete Artwork",
            onDismiss = { deleteConfirm = null },
            leftLabel = "Cancel",
            onLeftClick = { deleteConfirm = null },
            rightLabel = "Delete",
            onRightClick = {
                vm.deleteArtworks(ids)
                deleteConfirm = null
                selecting = false
                selectedIds.clear()
            },
            modifier = Modifier.widthIn(max = 330.dp).fillMaxWidth(0.86f),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Delete ${ids.size} artwork(s)? This cannot be undone.", color = Pc.TextSecondary, fontSize = 13.sp)
            }
        }
    }

    // About Dialog
    if (aboutOpen) {
        PcDialogShell(
            title = "About ProCraft",
            onDismiss = { aboutOpen = false },
            leftLabel = null,
            onLeftClick = null,
            rightLabel = "Done",
            onRightClick = { aboutOpen = false },
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.86f),
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Image(
                    painter = painterResource(R.drawable.procraft_mark),
                    contentDescription = "ProCraft Mark",
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
                )
                Text("ProCraft Studio", color = Pc.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("Digital painting studio for Android", color = Pc.TextSecondary, fontSize = 13.sp)
                Text("Multi-layer compositing • Brush Studio • Harmony • Curves • Liquify • Animation Assist", color = Pc.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ArtworkCard(
    artwork: ArtworkRecord,
    selecting: Boolean,
    selected: Boolean,
    swiped: Boolean,
    inStack: Boolean,
    onSwipeChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onUnstack: () -> Unit,
) {
    val thumb by produceState<ImageBitmap?>(initialValue = null, artwork.id, artwork.updatedAtMs) {
        value = withContext(Dispatchers.IO) {
            artwork.thumbnailPath?.let { p -> BitmapFactory.decodeFile(p)?.asImageBitmap() }
        }
    }
    var dragOffset by remember(swiped) { mutableFloatStateOf(if (swiped) -220f else 0f) }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.1f),
        ) {
            // Swipe Action Tray underneath
            if (swiped) {
                Row(
                    Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(end = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onSwipeChanged(false); onRename() }, modifier = Modifier.size(44.dp).clip(CircleShape).background(Pc.PanelSurface)) {
                        Icon(PcIcons.MenuRename, contentDescription = "Rename", tint = Pc.TextPrimary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onShare, modifier = Modifier.size(44.dp).clip(CircleShape).background(Pc.PanelSurface)) {
                        Icon(PcIcons.MenuShare, contentDescription = "Share", tint = Pc.TextPrimary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDuplicate, modifier = Modifier.size(44.dp).clip(CircleShape).background(Pc.PanelSurface)) {
                        Icon(PcIcons.MenuDuplicate, contentDescription = "Duplicate", tint = Pc.TextPrimary, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(44.dp).clip(CircleShape).background(Pc.Destructive)) {
                        Icon(PcIcons.MenuTrash, contentDescription = "Delete", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Box(
                Modifier
                    .offset { IntOffset(dragOffset.roundToInt(), 0) }
                    .fillMaxSize()
                    .shadow(8.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Pc.GalleryCell)
                    .border(if (selected) 2.5.dp else 1.dp, if (selected) Pc.Accent else Pc.Divider, RoundedCornerShape(8.dp))
                    .pointerInput(artwork.id, selecting) {
                        if (!selecting) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val open = dragOffset < -80f
                                    dragOffset = if (open) -210f else 0f
                                    onSwipeChanged(open)
                                },
                            ) { _, delta ->
                                dragOffset = (dragOffset + delta).coerceIn(-220f, 0f)
                            }
                        }
                    }
                    .clickable(onClick = onTap),
            ) {
                if (thumb != null) {
                    Image(
                        bitmap = thumb!!,
                        contentDescription = artwork.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (selecting) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (selected) Pc.Accent else Color(0x88000000))
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = artwork.title,
                color = Pc.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onRename),
            )
            if (inStack) {
                Text(
                    "Unstack",
                    color = Pc.Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.clickable(onClick = onUnstack),
                )
            }
        }
        Text("${artwork.width} × ${artwork.height}px", color = Pc.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun StackCard(
    stack: GalleryItem.StackGroup,
    selecting: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onRename: () -> Unit,
) {
    val lead = stack.items.firstOrNull()
    val thumb by produceState<ImageBitmap?>(initialValue = null, lead?.id, lead?.updatedAtMs) {
        value = withContext(Dispatchers.IO) {
            lead?.thumbnailPath?.let { p -> BitmapFactory.decodeFile(p)?.asImageBitmap() }
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.1f)
                .clickable(onClick = onTap),
        ) {
            // Back stack card layer
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = 10.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Pc.PanelSurface)
                    .border(1.dp, Pc.Divider, RoundedCornerShape(8.dp)),
            )
            // Front stack card
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = 8.dp, end = 8.dp)
                    .shadow(8.dp, RoundedCornerShape(8.dp))
                    .clip(RoundedCornerShape(8.dp))
                    .background(Pc.GalleryCell)
                    .border(if (selected) 2.5.dp else 1.dp, if (selected) Pc.Accent else Pc.Divider, RoundedCornerShape(8.dp)),
            ) {
                if (thumb != null) {
                    Image(bitmap = thumb!!, contentDescription = stack.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LucideIcons.CardStack, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                    Text("${stack.items.size}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                if (selecting) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (selected) Pc.Accent else Color(0x88000000))
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, contentDescription = "Selected Stack", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        Text(
            text = stack.name,
            color = Pc.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable(onClick = onRename),
        )
        Text("${stack.items.size} Artworks", color = Pc.TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun RenameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    PcDialogShell(
        title = title,
        onDismiss = onDismiss,
        leftLabel = "Cancel",
        onLeftClick = onDismiss,
        rightLabel = "Save",
        onRightClick = { onConfirm(text) },
        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(0.86f),
    ) {
        Column(Modifier.padding(14.dp)) {
            PcInlineTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Title",
                inputTag = "rename_input",
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
