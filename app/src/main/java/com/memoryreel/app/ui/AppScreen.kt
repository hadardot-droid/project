package com.memoryreel.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memoryreel.app.ExportState
import com.memoryreel.app.ReelFormat
import com.memoryreel.app.ReelStyle
import com.memoryreel.app.ReelViewModel
import com.memoryreel.app.SlideshowRenderer
import kotlin.math.roundToInt

@Composable
fun AppScreen(vm: ReelViewModel) {
    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ReelViewModel.MAX_PHOTOS)
    ) { uris -> vm.addPhotos(uris) }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.setMusic(uri)
    }
    val launchPicker = {
        pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Surface(Modifier.fillMaxSize(), color = Bg) {
        if (vm.photos.isEmpty()) {
            EmptyScreen(loading = vm.loading, onPick = launchPicker)
        } else {
            EditorScreen(vm, onAddPhotos = launchPicker, onPickMusic = { pickMusic.launch("audio/*") })
        }
    }
    ExportDialog(vm)

    val view = LocalView.current
    val running = vm.exportState is ExportState.Running
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }
}

// ---------------------------------------------------------------- empty state

@Composable
private fun EmptyScreen(loading: Boolean, onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StackedCards()
        Spacer(Modifier.height(36.dp))
        Text(
            "הופכים תמונות לסרטון זיכרונות",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Ink,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "בוחרים תמונות מהגלריה, והאפליקציה מסדרת אותן לפי תאריך, מוסיפה תנועה, מעברים, כותרת ומוזיקה, ושומרת סרטון מוכן לשיתוף.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = Muted,
        )
        Spacer(Modifier.height(32.dp))
        if (loading) {
            CircularProgressIndicator(color = Pink)
        } else {
            Button(
                onClick = onPick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(28.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("בחירת תמונות", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun StackedCards() {
    val brushes = listOf(
        Brush.linearGradient(listOf(Plum, Color(0xFF8E3FA8))),
        Brush.linearGradient(listOf(Gold, Peach)),
        Brush.linearGradient(listOf(Peach, Pink)),
    )
    val angles = listOf(-14f, 9f, -2f)
    val offsets = listOf(-34, 30, 0)
    Box(Modifier.size(width = 240.dp, height = 220.dp), contentAlignment = Alignment.Center) {
        brushes.forEachIndexed { i, brush ->
            Box(
                Modifier
                    .offset(x = offsets[i].dp)
                    .rotate(angles[i])
                    .size(width = 128.dp, height = 168.dp)
                    .shadow(12.dp, RoundedCornerShape(14.dp))
                    .background(Color(0xFFF7F1E3), RoundedCornerShape(14.dp))
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(brush)
            )
        }
        Box(
            Modifier
                .size(56.dp)
                .background(Color(0xCC14101A), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}

// ---------------------------------------------------------------- editor

@Composable
private fun EditorScreen(vm: ReelViewModel, onAddPhotos: () -> Unit, onPickMusic: () -> Unit) {
    val cfg = vm.config()
    val version = vm.thumbsVersion
    val renderer = remember(cfg, version) { SlideshowRenderer(vm.store, cfg) }

    var playing by remember { mutableStateOf(true) }
    var time by remember { mutableFloatStateOf(0f) }
    var tick by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(renderer) {
        if (time > renderer.totalSeconds) time = 0f
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L && playing && renderer.totalSeconds > 0f) {
                    time = (time + (now - last) / 1_000_000_000f) % renderer.totalSeconds
                }
                last = now
                tick++
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("זיכרונות", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { selected = null; vm.clearAll() }) { Text("התחלה מחדש") }
        }
        Spacer(Modifier.height(8.dp))

        // Live preview: the exact renderer the export uses.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .heightIn(max = 440.dp)
                    .aspectRatio(vm.format.ratio, matchHeightConstraintsFirst = true)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black)
                    .clickable { playing = !playing },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (tick < 0) return@Canvas
                    drawIntoCanvas { c ->
                        renderer.draw(c.nativeCanvas, size.width.toInt(), size.height.toInt(), time, blocking = false)
                    }
                }
                if (!playing) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "הפעלה",
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(68.dp)
                            .background(Color(0x77000000), CircleShape)
                            .padding(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        PlaybackBar(time = { time }, total = renderer.totalSeconds, count = cfg.photos.size)

        // Photo strip
        SectionTitle("התמונות (${cfg.photos.size})")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(cfg.photos, key = { _, u -> u.toString() }) { index, uri ->
                val thumb = remember(uri, version) { vm.store.thumb(uri)?.asImageBitmap() }
                val isSel = uri == selected
                Box(
                    Modifier
                        .size(width = 64.dp, height = 84.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Card)
                        .border(if (isSel) 3.dp else 0.dp, if (isSel) Pink else Color.Transparent, RoundedCornerShape(10.dp))
                        .clickable {
                            selected = if (isSel) null else uri
                            time = renderer.slideStart(index) + 0.4f
                        },
                ) {
                    if (thumb != null) {
                        Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Text(
                        "${index + 1}",
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(4.dp)
                            .background(Color(0x99000000), RoundedCornerShape(6.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
            item {
                Box(
                    Modifier
                        .size(width = 64.dp, height = 84.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CardHigh)
                        .clickable(enabled = !vm.loading && cfg.photos.size < ReelViewModel.MAX_PHOTOS) { onAddPhotos() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (vm.loading) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Add, contentDescription = "הוספת תמונות", tint = Ink)
                }
            }
        }
        val sel = selected
        if (sel != null && sel in cfg.photos) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.move(sel, -1) }) { Text("להקדים") }
                TextButton(onClick = { vm.move(sel, 1) }) { Text("לאחר") }
                TextButton(onClick = { vm.remove(sel); selected = null }) { Text("הסרה", color = Peach) }
            }
        } else {
            Text("נגיעה בתמונה כדי לקפוץ אליה, להזיז או להסיר", color = Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }

        // Style
        SectionTitle("סגנון")
        ChipRow {
            ReelStyle.entries.forEach { s ->
                Chip(s.label, vm.style == s) { vm.selectStyle(s); time = 0f }
            }
            Chip("הפתיעו אותי", false) { vm.surpriseMe(); time = 0f }
        }

        // 3D depth (on-device AI)
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("אפקט עומק תלת־ממדי", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                val ready = remember(vm.depthReady, cfg.photos) { cfg.photos.count { vm.store.hasDepth(it) } }
                val status = when {
                    !vm.depthAvailable -> "לא זמין במכשיר הזה"
                    ready < cfg.photos.size -> "מנתח עומק על המכשיר… $ready מתוך ${cfg.photos.size}"
                    else -> "בינה מלאכותית על הטלפון מזהה מה קרוב ומה רחוק, והמצלמה זזה בתלת־ממד"
                }
                Text(status, color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = vm.depth3d && vm.depthAvailable,
                enabled = vm.depthAvailable,
                onCheckedChange = { vm.depth3d = it },
            )
        }

        // Format
        SectionTitle("גודל")
        ChipRow {
            ReelFormat.entries.forEach { f -> Chip(f.label, vm.format == f) { vm.format = f } }
        }

        // Pace
        SectionTitle("קצב: ${formatSeconds1(vm.secondsPerPhoto)} שניות לתמונה")
        Slider(
            value = vm.secondsPerPhoto,
            onValueChange = { vm.secondsPerPhoto = (it * 2f).roundToInt() / 2f },
            valueRange = 1f..6f,
            steps = 9,
        )

        // Title
        SectionTitle("כותרת פתיחה")
        OutlinedTextField(
            value = vm.title,
            onValueChange = { vm.title = it; time = 1.6f },
            label = { Text("כותרת") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = vm.subtitle,
            onValueChange = { vm.subtitle = it; time = 1.6f },
            label = { Text("שורה שנייה (למשל תאריך)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // Music
        SectionTitle("מוזיקה")
        if (vm.musicUri == null) {
            OutlinedButton(onClick = onPickMusic, modifier = Modifier.fillMaxWidth()) { Text("בחירת שיר מהטלפון") }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    vm.musicName,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onPickMusic) { Text("החלפה") }
                TextButton(onClick = { vm.setMusic(null) }) { Text("הסרה", color = Peach) }
            }
        }
        Text(
            "השיר יחזור או ייחתך לאורך הסרטון, עם דהייה בסוף. הוא נשמע בסרטון המוכן, לא בתצוגה המקדימה.",
            color = Muted,
            fontSize = 13.sp,
        )

        // Quality
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("איכות גבוהה (1080p)", color = Ink)
                Text("כבוי: 720p, נוצר מהר יותר", color = Muted, fontSize = 13.sp)
            }
            Switch(checked = vm.highQuality, onCheckedChange = { vm.highQuality = it })
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { playing = false; vm.startExport() },
            enabled = cfg.photos.isNotEmpty() && !vm.loading,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = RoundedCornerShape(29.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Pink),
        ) {
            Text("יצירת סרטון", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlaybackBar(time: () -> Float, total: Float, count: Int) {
    val t = time()
    Column {
        LinearProgressIndicator(
            progress = { if (total > 0f) (time() / total).coerceIn(0f, 1f) else 0f },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(2.dp)),
            color = Pink,
            trackColor = CardHigh,
        )
        Row(Modifier.padding(top = 4.dp)) {
            Text("${clock(t)} / ${clock(total)}", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Text("$count תמונות", color = Muted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Ink,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Pink,
            selectedLabelColor = Color(0xFF2A0E1B),
        ),
    )
}

// ---------------------------------------------------------------- export dialog

@Composable
private fun ExportDialog(vm: ReelViewModel) {
    val context = LocalContext.current
    when (val s = vm.exportState) {
        ExportState.Idle -> Unit
        is ExportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("יוצרים את הסרטון…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = Pink)
                    Spacer(Modifier.height(10.dp))
                    Text("${(s.progress * 100).toInt()}%", fontWeight = FontWeight.Bold)
                    Text("זה לוקח בערך דקה לכל חצי דקה של סרטון. כדאי להישאר באפליקציה.", color = Muted, fontSize = 13.sp)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.cancelExport() }) { Text("ביטול") } },
        )
        is ExportState.Done -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("הסרטון מוכן") },
            text = { Text("הסרטון נשמר בגלריה, בתיקייה Movies/Memories.") },
            confirmButton = { Button(onClick = { shareVideo(context, s.uri) }) { Text("שיתוף") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { openVideo(context, s.uri) }) { Text("צפייה") }
                    TextButton(onClick = { vm.dismissExport() }) { Text("סגירה") }
                }
            },
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissExport() },
            title = { Text("לא הצלחנו ליצור את הסרטון") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.dismissExport() }) { Text("הבנתי") } },
        )
    }
}

private fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "שיתוף הסרטון"))
}

private fun openVideo(context: Context, uri: Uri) {
    val view = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(view)
    } catch (e: ActivityNotFoundException) {
        shareVideo(context, uri)
    }
}

private fun clock(seconds: Float): String {
    val s = seconds.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatSeconds1(v: Float): String =
    if (v % 1f == 0f) v.toInt().toString() else "%.1f".format(v)
