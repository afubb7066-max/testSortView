package com.mohammadjafari.sortview.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import com.mohammadjafari.sortview.R
import com.mohammadjafari.sortview.media.ANIM_EXTS
import com.mohammadjafari.sortview.media.AUDIO_EXTS
import com.mohammadjafari.sortview.media.EXECUTABLE_OPEN_EXTS
import com.mohammadjafari.sortview.media.TEXT_PREVIEW_EXTS
import com.mohammadjafari.sortview.media.SystemPlayer
import com.mohammadjafari.sortview.media.playsAsVideo
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Preview resolution for the static pane — the Android counterpart of the
 * desktop HIGH_PREVIEW_MULT (main.py L318: thumbnail_size * 3, minimum
 * 560/680): the same intent (a preview clearly larger than a grid tile)
 * bounded to what a phone screen can show.
 */
private const val PREVIEW_PX = 1024

/** Max render size for one PDF page in the multi-page browser (ROADMAP فاز ۳). */
private const val PDF_PAGE_PX = 1440

/**
 * Full-screen preview dialog — the mobile counterpart of the desktop
 * preview panel (main.py show_preview L3446 / _reset_preview L3146):
 *
 *  - video: plays embedded with Media3/ExoPlayer (desktop: Qt Multimedia
 *    with libVLC/ffplay fallbacks), auto-play on open, loops forever
 *    (desktop setLoops(-1) / input-repeat=65535); a decoder error surfaces
 *    as the desktop status text "Video playback failed" over the type card;
 *  - audio: plays WITHOUT a video surface — the gradient type card stays
 *    visible while the sound plays (desktop audio mode, L3309-3334);
 *  - image: full-screen Coil image with pinch zoom; GIFs animate via Coil
 *    (desktop QMovie, L3468-3475);
 *  - pdf: multi-page pager rendered with PdfRenderer (ROADMAP فاز ۳ extra —
 *    the desktop panel shows page 1 only, the thumbnail engine covers it);
 *  - text/code/subtitle: line-numbered monospace view (ROADMAP فاز ۳ extra,
 *    bounded to 300 KB / 3000 lines);
 *  - everything else (archive/code/unknown): the thumbnail engine renders
 *    the high-res preview (first archive image) and the gradient type card
 *    is the fallback — the same chain the desktop uses with
 *    cached_thumbnail_bytes -> generate_type_card_bytes (L3476-3484);
 *  - "Open with system player" hands the file to the default Android app
 *    via ACTION_VIEW + FileProvider (desktop QDesktopServices, L3407), with
 *    the same bilingual executable warning (L3382-3405);
 *  - closing the dialog releases the player and its file handle
 *    immediately (desktop _reset_preview); the view model additionally
 *    waits 200 ms before moving a file that was just previewed.
 */
@Composable
fun PreviewDialog(
    vm: SortViewModel,
    file: SortFile,
    thumbGeneration: Int,
) {
    Dialog(
        onDismissRequest = { vm.closePreview() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- top bar: file name + close (mobile stand-in for the panel title) ---
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = { vm.closePreview() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                        }
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // --- content pane ---
                val ext = file.ext.lowercase()
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        playsAsVideo(File(file.path), file.ext) ->
                            VideoPane(vm, file)
                        ext in AUDIO_EXTS ->
                            AudioPane(vm, file)
                        file.group == com.mohammadjafari.sortview.media.MediaGroup.IMAGE || ext in ANIM_EXTS ->
                            ImagePane(file)
                        ext == "pdf" ->
                            PdfPagerPane(file)
                        ext in TEXT_PREVIEW_EXTS ->
                            TextPane(file)
                        else ->
                            StaticPane(vm, file, thumbGeneration)
                    }
                }

                // --- bottom bar: open with the system player (desktop btn_open_ext) ---
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OpenWithSystemButton(vm, file)
                    }
                }
            }
        }
    }
}

// ---------------- video (desktop QtVideoWidget L1696 + _start_embedded_video L3213) ----------------

@Composable
private fun VideoPane(vm: SortViewModel, file: SortFile) {
    val context = LocalContext.current
    val item = remember(file.path) { MediaItem.fromUri(android.net.Uri.fromFile(File(file.path))) }
    val player = remember(file.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(item)
            // Loop forever like the desktop (setLoops(-1) / input-repeat).
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Auto-play on open (desktop plays immediately on selection).
            playWhenReady = true
            prepare()
        }
    }
    // Release the file handle the moment the dialog leaves composition —
    // the desktop equivalent of _reset_preview before a move (L3628-3631).
    DisposableEffect(file.path) {
        onDispose { player.release() }
    }

    // Unsupported/corrupt codec (wmv/flv on many devices) — the desktop
    // reports "Video playback failed" (L68 of app_i18n.py); here the type
    // card + the same message replace the dead surface.
    var failed by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failed = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Stop → hide the video surface and show the static card, exactly like
    // the desktop Stop button (L3584-3590); Play restarts from the beginning.
    var stopped by remember { mutableStateOf(false) }

    if (stopped || failed) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TypeCard(file, Modifier.fillMaxWidth(0.7f).height(240.dp))
            if (failed) {
                Text(
                    text = stringResource(R.string.err_video_playback_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply { useController = false }
                },
                update = { it.player = player },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            PlaybackControls(
                player = player,
                onRestart = {
                    player.setMediaItem(item)
                    player.prepare()
                    player.play()
                },
                onStop = {
                    // stop() is asynchronous (desktop comment L1808-1810): clear
                    // the media too so the file handle drops immediately.
                    player.stop()
                    player.clearMediaItems()
                    stopped = true
                },
            )
        }
    }
}

// ---------------- audio (desktop _start_embedded_audio L3309: card stays visible) ----------------

@Composable
private fun AudioPane(vm: SortViewModel, file: SortFile) {
    val context = LocalContext.current
    val item = remember(file.path) { MediaItem.fromUri(android.net.Uri.fromFile(File(file.path))) }
    val player = remember(file.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(item)
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(file.path) {
        onDispose { player.release() }
    }

    var stopped by remember { mutableStateOf(false) }

    // No video surface — the gradient type card stays on screen while the
    // sound plays (desktop audio mode parity, main.py L3333).
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            TypeCard(file, Modifier.fillMaxWidth(0.7f).height(240.dp))
        }
        if (!stopped) {
            PlaybackControls(
                player = player,
                onRestart = {
                    player.setMediaItem(item)
                    player.prepare()
                    player.play()
                },
                onStop = {
                    player.stop()
                    player.clearMediaItems()
                    stopped = true
                },
            )
        }
    }
}

/**
 * Play/Pause + Stop + a position slider. The Play/Pause label is synced
 * with the real engine state every 400 ms — the desktop's
 * playback_sync_timer (main.py L2437-2441) that fixed the classic
 * "pause actually replays" confusion. The slider is a documented mobile
 * adaptation (the desktop preview has no seek control).
 */
@Composable
private fun PlaybackControls(
    player: Player,
    onRestart: () -> Unit,
    onStop: () -> Unit,
) {
    // 400 ms state poll — desktop playback_sync_timer parity (L2437).
    var playing by remember { mutableStateOf(player.isPlaying) }
    var positionMs by remember { mutableStateOf(0f) }
    var draggingMs by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (isActive) {
            playing = player.isPlaying
            if (draggingMs == null) {
                positionMs = if (player.duration > 0) player.currentPosition.toFloat() else 0f
            }
            delay(400)
        }
    }
    val durationMs = if (player.duration > 0) player.duration.toFloat() else 0f

    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (durationMs > 0f) {
                Slider(
                    value = (draggingMs ?: positionMs).coerceIn(0f, durationMs),
                    onValueChange = { draggingMs = it },
                    onValueChangeFinished = {
                        draggingMs?.let { player.seekTo(it.toLong()) }
                        draggingMs = null
                    },
                    valueRange = 0f..durationMs,
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatMs((draggingMs ?: positionMs).toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = formatMs(durationMs.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Desktop label rules: playing -> "Pause", else "Play";
                // after Stop a press restarts the clip from the beginning.
                TextButton(onClick = {
                    if (playing) player.pause() else player.play()
                }) {
                    Text(stringResource(if (playing) R.string.btn_pause else R.string.btn_play))
                }
                TextButton(onClick = onStop) {
                    Text(stringResource(R.string.btn_stop))
                }
            }
        }
    }
}

// ---------------- image (desktop _load_image_pixmap_safely L3180 + QMovie L3468) ----------------

@Composable
private fun ImagePane(file: SortFile) {
    // Pinch zoom — a phone has no resizeEvent to rescale the pixmap
    // (desktop _rescale_preview_pixmap L3205), so the user zooms by hand.
    var scale by remember { mutableStateOf(1f) }
    val transformable = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
    }
    val isGif = file.ext.lowercase() in ANIM_EXTS
    SubcomposeAsyncImage(
        model = File(file.path),
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .transformable(transformable),
        loading = { TypeCard(file, Modifier.fillMaxSize()) },
        error = {
            // Desktop L3467 / L3475: distinct "cannot preview" texts.
            Text(
                text = stringResource(
                    if (isGif) R.string.err_cannot_preview_gif else R.string.err_cannot_preview_image,
                ),
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            )
        },
    )
}

// ---------------- pdf multi-page pager (ROADMAP فاز ۳ extra) ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PdfPagerPane(file: SortFile) {
    val renderer = remember(file.path) {
        try {
            val pfd = ParcelFileDescriptor.open(File(file.path), ParcelFileDescriptor.MODE_READ_ONLY)
            PdfRenderer(pfd)
        } catch (_: Exception) {
            null
        }
    }
    DisposableEffect(file.path) {
        onDispose {
            try {
                renderer?.close()
            } catch (_: Exception) {
            }
        }
    }
    val pageCount = renderer?.pageCount ?: 0
    if (renderer == null || pageCount <= 0) {
        // Corrupt/empty PDF -> the desktop fallback chain ends at the card.
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TypeCard(file, Modifier.fillMaxWidth(0.7f).height(240.dp))
        }
        return
    }
    val pagerState = rememberPagerState(pageCount = { pageCount })
    Column(modifier = Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().weight(1f)) { index ->
            val bmpState = produceState<Bitmap?>(null, file.path, index) {
                value = withContext(Dispatchers.IO) { renderPdfPage(renderer, index) }
            }
            val bmp = bmpState.value
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // Page still rendering (or unrecoverable) — a quiet spinner
                // is the mobile stand-in for the desktop's instant pixmap.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        Text(
            // "page x of y" as plain numerals — language-neutral.
            text = "${pagerState.currentPage + 1} / $pageCount",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** Render one PDF page on a worker thread; PdfRenderer itself is not thread-safe. */
private fun renderPdfPage(renderer: PdfRenderer, index: Int): Bitmap? = try {
    synchronized(renderer) {
        renderer.openPage(index).use { page ->
            val maxSide = maxOf(page.width, page.height).coerceAtLeast(1)
            val scale = PDF_PAGE_PX.toFloat() / maxSide
            val w = (page.width * scale).toInt().coerceIn(1, 2000)
            val h = (page.height * scale).toInt().coerceIn(1, 2000)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                // Pre-fill white so transparent PDF backgrounds look like paper.
                eraseColor(android.graphics.Color.WHITE)
            }.also { page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
        }
    }
} catch (_: Exception) {
    null
}

// ---------------- text / code / subtitle (ROADMAP فاز ۳ extra) ----------------

@Composable
private fun TextPane(file: SortFile) {
    val linesState = produceState<List<String>?>(null, file.path) {
        value = withContext(Dispatchers.IO) { readTextLinesLimited(file.path) }
    }
    val lines = linesState.value
    if (lines == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TypeCard(file, Modifier.fillMaxWidth(0.7f).height(240.dp))
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        itemsIndexed(lines) { i, line ->
            Text(
                // Line numbers are the documented Phase 3 promise; monospace
                // keeps the column stable. "…" marks a truncated read.
                text = if (line == "…") line else "%6d  %s".format(i + 1, line),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * Bounded text read: at most 300 KB and 3000 lines — a 100 MB log file must
 * not freeze the preview. Returns null when the file cannot be read at all
 * (the caller falls back to the type card).
 */
private fun readTextLinesLimited(
    path: String,
    maxBytes: Int = 300 * 1024,
    maxLines: Int = 3000,
): List<String>? {
    val out = mutableListOf<String>()
    return try {
        FileInputStream(path).use { fis ->
            BufferedReader(InputStreamReader(fis, Charsets.UTF_8)).use { br ->
                var consumed = 0
                while (out.size < maxLines) {
                    val line = br.readLine() ?: return out
                    consumed += line.length + 1
                    out.add(line)
                    if (consumed > maxBytes) {
                        out.add("…")
                        return out
                    }
                }
                out.add("…")
                out
            }
        }
    } catch (_: Exception) {
        null
    }
}

// ---------------- static preview (desktop show_preview L3476-3484 fallback chain) ----------------

@Composable
private fun StaticPane(
    vm: SortViewModel,
    file: SortFile,
    thumbGeneration: Int,
    forceTypeCard: Boolean = false,
) {
    val bmpState = produceState<Bitmap?>(initialValue = null, file.path, thumbGeneration) {
        value = if (forceTypeCard) null else vm.loadThumbnail(file, PREVIEW_PX)
    }
    val bmp = bmpState.value
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = file.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        // cached_thumbnail_bytes returned nothing -> the gradient type card
        // at high resolution is the desktop fallback (generate_type_card_bytes).
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            TypeCard(file, Modifier.fillMaxWidth(0.7f).height(240.dp))
        }
    }
}

// ---------------- open with the system player (desktop L3407 + confirm L3382) ----------------

@Composable
private fun OpenWithSystemButton(vm: SortViewModel, file: SortFile) {
    val context = LocalContext.current
    var confirmExe by remember { mutableStateOf(false) }

    TextButton(onClick = {
        if (file.ext.lowercase() in EXECUTABLE_OPEN_EXTS) {
            // Desktop EXECUTABLE_OPEN_EXTS guard (L3382-3405): launching a
            // program-like file must be an explicit decision.
            confirmExe = true
        } else {
            openWithSystem(vm, context, file)
        }
    }) {
        Text(stringResource(R.string.btn_open_with_system))
    }

    if (confirmExe) {
        AlertDialog(
            onDismissRequest = { confirmExe = false },
            title = { Text(stringResource(R.string.dlg_run_file_title)) },
            text = {
                // Desktop L3396: "This file looks like a program ({name}). Open it anyway?"
                Text(context.resolveStatus(statusOf(R.string.dlg_run_file_body, "name" to file.name)))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmExe = false
                    openWithSystem(vm, context, file)
                }) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = {
                // The safe answer is the default on the desktop (MsgBox_No).
                TextButton(onClick = {
                    confirmExe = false
                    vm.notifyOpeningCancelled()
                }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

private fun openWithSystem(vm: SortViewModel, context: android.content.Context, file: SortFile) {
    val error = SystemPlayer.openWithSystemApp(context, file.path, file.ext)
    if (error == null) {
        vm.notifyOpenedWithSystemPlayer()
    } else {
        vm.notifyCannotOpen(error)
    }
}

/** "m:ss" position text for the seek slider (mobile adaptation, no desktop counterpart). */
private fun formatMs(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
