package com.mohammadjafari.sortview.ui

import android.graphics.Bitmap
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Painter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.waitForUpOrCancellation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import com.mohammadjafari.sortview.R
import com.mohammadjafari.sortview.data.Category
import com.mohammadjafari.sortview.fs.Paths
import com.mohammadjafari.sortview.media.ANIM_EXTS
import com.mohammadjafari.sortview.media.AUDIO_EXTS
import com.mohammadjafari.sortview.media.MediaGroup
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Poster/preview resolution for the feed's non-playing pages. */
private const val FEED_POSTER_PX = 1024

/** Double-tap seek step (YouTube-style). */
private const val FEED_SEEK_STEP_S = 10

/**
 * Reels-style feed (v1.1.0) — the phone-first sorting surface:
 *
 *  - ONE file per screen, [VerticalPager] swiping vertically (never
 *    horizontal — vertical is reserved for paging, horizontal for sending);
 *  - video/audio play through ONE shared ExoPlayer owned by the screen:
 *    the settled page drives it, neighbors show the thumbnail-engine
 *    poster, so memory stays bounded on long sessions (a player pool of 2
 *    was deliberately rejected: a half-moved queue of giant videos OOMs
 *    low-end phones long before latency matters);
 *  - single tap flips play/pause IMMEDIATELY; a second tap within 260 ms
 *    undoes it and seeks +-10 s by tap zone (left/right thirds) — the
 *    "no 300 ms tap delay" rule from the v1.0.2 grid fix, kept here;
 *  - a thin seek slider rides above the quick dock;
 *  - the paper-plane rail button opens the SEND bottom sheet (all
 *    categories, searchable past 8); sending releases the player, hides
 *    the file locally so pager indexes never jump, routes through
 *    [SortViewModel.sendPathToCategory] (200 ms settle + the v1.0.3
 *    batch-open-before-enqueue undo bookkeeping) and offers Undo on a
 *    snackbar;
 *  - the quick dock pins up to 6 categories (AndroidPrefs, never
 *    config.json — contract #1); horizontal swipe left/right sends to the
 *    configurable targets (OFF by default — no accidental moves);
 *  - images pinch-zoom (paging pauses while zoomed), GIFs animate via
 *    Coil, everything else shows the desktop gradient type card;
 *  - the layout direction is forced LTR so the rail/dock anchors never
 *    jump in the Persian locale (texts still shape RTL), and the screen is
 *    portrait-locked at the manifest level (v1.0.3).
 */
@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3Api::class,
)
@Composable
fun FeedScreen(vm: SortViewModel, onBack: () -> Unit) {
    val st by vm.state.collectAsState()
    val prefs by vm.feedPrefs.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Files hidden by an in-flight send keep the pager indexes stable; a
    // rescan (thumbGeneration bump, also fired by undo) rebuilds the list.
    var hidden by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(st.thumbGeneration) { hidden = emptySet() }
    LaunchedEffect(st.errorPaths) {
        val returned = hidden intersect st.errorPaths
        if (returned.isNotEmpty()) hidden = hidden - returned
    }
    val feedFiles by remember { derivedStateOf { st.files.filter { it.path !in hidden } } }

    val pagerState = rememberPagerState(pageCount = { feedFiles.size })

    var zoomed by remember { mutableStateOf(false) }
    var showSendSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var resumePlayback by remember { mutableStateOf(false) }
    var playerFailed by remember { mutableStateOf(false) }
    var playingPath by remember { mutableStateOf<String?>(null) }
    var pausedNow by remember { mutableStateOf(false) }
    var seekHint by remember { mutableStateOf<String?>(null) }
    var swipeX by remember { mutableFloatStateOf(0f) }
    var swiping by remember { mutableStateOf(false) }
    var boxWidthPx by remember { mutableIntStateOf(0) }

    BackHandler { onBack() }

    val currentFile = feedFiles.getOrNull(pagerState.currentPage)
    val currentIsMedia = currentFile?.let {
        it.group == MediaGroup.VIDEO || it.ext.lowercase() in AUDIO_EXTS
    } == true

    val leftCat = prefs.swipeLeftPath?.let { p ->
        st.categories.firstOrNull { Paths.samePath(File(it.path), File(p)) }
    }
    val rightCat = prefs.swipeRightPath?.let { p ->
        st.categories.firstOrNull { Paths.samePath(File(it.path), File(p)) }
    }

    val quickCats = remember(st.categories, prefs.quickCategories) {
        if (prefs.quickCategories.isEmpty()) {
            st.categories.take(SortViewModel.FEED_QUICK_MAX)
        } else {
            prefs.quickCategories.mapNotNull { p ->
                st.categories.firstOrNull { c -> Paths.samePath(File(c.path), File(p)) }
            }.take(SortViewModel.FEED_QUICK_MAX)
        }
    }

    // ONE shared player for the whole feed: the settled page swaps its
    // media item; neighbors are poster-only. Audio focus handled, loop
    // forever like the desktop preview.
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                playerFailed = true
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Backgrounding pauses; returning resumes only if we were playing.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumePlayback = player.isPlaying
                    player.pause()
                }
                Lifecycle.Event.ON_RESUME -> if (resumePlayback) player.play()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun seekBy(seconds: Int) {
        val dur = player.duration
        val target = player.currentPosition + seconds * 1000L
        player.seekTo(if (dur > 0) target.coerceIn(0L, dur) else target.coerceAtLeast(0L))
    }

    fun sendCurrent(categoryPath: String, categoryName: String) {
        val f = feedFiles.getOrNull(pagerState.currentPage) ?: return
        if (sending) return
        sending = true
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            },
        )
        // Drop the shared player's handle on the file BEFORE the enqueue —
        // sendPathToCategory waits 200 ms like the grid move path.
        player.pause()
        player.clearMediaItems()
        playingPath = null
        resumePlayback = false
        // Hide locally so the pager indexes stay stable while the engine
        // moves the file (st.files catches up on Finished).
        hidden = hidden + f.path
        scope.launch {
            vm.sendPathToCategory(f.path, categoryPath)
            val result = snackbar.showSnackbar(
                message = context.resolveStatus(statusOf(R.string.feed_moved_to, "name" to categoryName)),
                actionLabel = context.getString(R.string.btn_undo_move),
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                vm.undoLastMove()
            }
        }
        scope.launch {
            delay(350)
            sending = false
        }
    }

    // Drive the shared player from the settled page.
    LaunchedEffect(pagerState.settledPage, currentFile?.path, currentIsMedia) {
        playerFailed = false
        val f = feedFiles.getOrNull(pagerState.settledPage)
        val isMedia = f?.let {
            it.group == MediaGroup.VIDEO || it.ext.lowercase() in AUDIO_EXTS
        } == true
        if (f == null || !isMedia) {
            player.pause()
            player.clearMediaItems()
            playingPath = null
            resumePlayback = false
        } else if (playingPath != f.path) {
            player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(File(f.path))))
            player.prepare()
            playingPath = f.path
            resumePlayback = true
            player.play()
        }
        // else: this item is already loaded — do nothing (the user may
        // have paused it on purpose; a restart here would fight the tap
        // toggle).
    }

    // 400 ms play-state poll for the paused indicator (preview parity).
    LaunchedEffect(player) {
        while (isActive) {
            pausedNow = !player.isPlaying
            delay(400)
        }
    }

    LaunchedEffect(seekHint) {
        if (seekHint != null) {
            delay(500)
            seekHint = null
        }
    }

    // Clamp the pager when the list shrinks past the current page.
    LaunchedEffect(feedFiles.size) {
        if (feedFiles.isNotEmpty() && pagerState.currentPage > feedFiles.size - 1) {
            pagerState.scrollToPage(feedFiles.size - 1)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .safeDrawingPadding()
                .onSizeChanged { boxWidthPx = it.width },
        ) {
            if (feedFiles.isEmpty()) {
                // Everything sorted (or nothing scanned) — a calm end state.
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.feed_all_done_title),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (st.canUndo) {
                            TextButton(onClick = { vm.undoLastMove() }) {
                                Text(stringResource(R.string.btn_undo_move))
                            }
                        }
                        TextButton(onClick = onBack) {
                            Text(stringResource(R.string.feed_back_to_grid))
                        }
                    }
                }
            } else {
                VerticalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxSize()
                        // Single tap = play/pause NOW; quick second tap =
                        // undo + seek by zone (left/right thirds).
                        .pointerInput(currentIsMedia, zoomed) {
                            if (!currentIsMedia || zoomed) return@pointerInput
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                val up = waitForUpOrCancellation()
                                if (up != null) {
                                    if (player.isPlaying) player.pause() else player.play()
                                    val second = withTimeoutOrNull(260) {
                                        awaitFirstDown(requireUnconsumed = false)
                                    }
                                    if (second != null) {
                                        val up2 = waitForUpOrCancellation()
                                        if (up2 != null) {
                                            if (player.isPlaying) player.pause() else player.play()
                                            val w = size.width.toFloat()
                                            val x = up2.position.x
                                            if (x < w / 3f) {
                                                seekBy(-FEED_SEEK_STEP_S)
                                                seekHint = "-${FEED_SEEK_STEP_S}s"
                                            } else if (x > w * 2f / 3f) {
                                                seekBy(FEED_SEEK_STEP_S)
                                                seekHint = "+${FEED_SEEK_STEP_S}s"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Horizontal swipe = send to the configured target
                        // (OFF by default; a drag toward a disabled side
                        // never accumulates offset, so nothing fires).
                        .pointerInput(leftCat?.path, rightCat?.path, zoomed) {
                            val leftOn = leftCat != null
                            val rightOn = rightCat != null
                            if ((!leftOn && !rightOn) || zoomed) return@pointerInput
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    swiping = true
                                    swipeX = 0f
                                },
                                onDragEnd = {
                                    swiping = false
                                    val threshold = size.width * 0.35f
                                    val cat = when {
                                        swipeX <= -threshold && leftOn -> leftCat
                                        swipeX >= threshold && rightOn -> rightCat
                                        else -> null
                                    }
                                    if (cat != null) sendCurrent(cat.path, cat.name)
                                    swipeX = 0f
                                },
                                onDragCancel = {
                                    swiping = false
                                    swipeX = 0f
                                },
                            ) { change, dragAmount ->
                                val next = swipeX + dragAmount
                                val blocked = (next < 0f && !leftOn) || (next > 0f && !rightOn)
                                if (blocked) {
                                    swipeX = 0f
                                } else {
                                    swipeX = next
                                    change.consume()
                                }
                            }
                        },
                    userScrollEnabled = !zoomed,
                    beyondViewportPageCount = 1,
                    key = { idx -> feedFiles.getOrNull(idx)?.path ?: "idx-$idx" },
                ) { idx ->
                    val f = feedFiles.getOrNull(idx)
                    if (f != null) {
                        val isCurrent = pagerState.currentPage == idx
                        when {
                            f.group == MediaGroup.IMAGE ->
                                FeedImagePage(f, onZoomChange = { zoomed = it })
                            f.group == MediaGroup.VIDEO ->
                                FeedVideoSurface(player, isCurrent, f, vm, st.thumbGeneration)
                            f.ext.lowercase() in AUDIO_EXTS ->
                                FeedAudioCard(f)
                            else ->
                                FeedPoster(f, vm, st.thumbGeneration)
                        }
                    }
                }
            }

            // --- top bar: back, folder + counter, busy, settings ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_cancel),
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentFile?.let { File(it.path).parent ?: "" } ?: "",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${pagerState.currentPage + 1} / ${feedFiles.size}",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (st.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 6.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                }
                IconButton(onClick = { showSettings = true }) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.feed_settings_title),
                        tint = Color.White,
                    )
                }
            }

            // --- paused indicator (media only, on decoder failure hidden) ---
            if (pausedNow && currentIsMedia && !playerFailed && feedFiles.isNotEmpty()) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.Center).size(72.dp),
                )
            }

            // --- player failure overlay ---
            if (playerFailed && currentIsMedia && currentFile != null) {
                Text(
                    text = stringResource(
                        if (currentFile.group == MediaGroup.AUDIO) {
                            R.string.err_audio_playback_failed
                        } else {
                            R.string.err_video_playback_failed
                        },
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 170.dp),
                )
            }

            // --- seek hint (+10s / -10s) ---
            seekHint?.let { hint ->
                Text(
                    text = hint,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // --- swipe veil: the target category it will send to ---
            if (swiping && swipeX != 0f) {
                val w = boxWidthPx.coerceAtLeast(1).toFloat()
                val progress = (abs(swipeX) / (w * 0.35f)).coerceIn(0f, 1f)
                val cat = if (swipeX < 0) leftCat else rightCat
                if (cat != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f * progress)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = (if (swipeX < 0) "← " else "→ ") + cat.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
            }

            // --- right rail: send (+ undo) ---
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                RailButton(
                    onClick = { showSendSheet = true },
                    icon = {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = stringResource(R.string.feed_send_title),
                            tint = Color.White,
                        )
                    },
                    label = stringResource(R.string.feed_send_title),
                )
                if (st.canUndo) {
                    RailButton(
                        onClick = { vm.undoLastMove() },
                        icon = {
                            Icon(
                                painter = remember { undoPainter(Color.White) },
                                contentDescription = stringResource(R.string.btn_undo_move),
                            )
                        },
                        label = stringResource(R.string.btn_undo_move),
                    )
                }
            }

            // --- bottom: seek slider + quick dock ---
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FeedSeekbar(player = player, visible = currentIsMedia && !playerFailed)
                QuickDock(cats = quickCats, onSend = { cat -> sendCurrent(cat.path, cat.name) })
            }

            // --- snackbar: "Sent to X" + Undo ---
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 150.dp),
            )
        }
    }

    // --- send sheet: every category, searchable past 8 ---
    if (showSendSheet) {
        ModalBottomSheet(onDismissRequest = { showSendSheet = false }) {
            var query by remember { mutableStateOf("") }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.feed_send_title), style = MaterialTheme.typography.titleMedium)
                if (st.categories.size > 8) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.feed_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (st.categories.isEmpty()) {
                    Text(
                        text = stringResource(R.string.feed_no_categories),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val cats = if (query.isBlank()) {
                        st.categories
                    } else {
                        st.categories.filter {
                            it.name.contains(query, ignoreCase = true) ||
                                it.path.contains(query, ignoreCase = true)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        cats.forEach { cat ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        showSendSheet = false
                                        sendCurrent(cat.path, cat.name)
                                    }
                                    .padding(horizontal = 4.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = cat.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = cat.path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    // --- feed settings: quick dock pins + swipe targets ---
    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text(stringResource(R.string.feed_settings_title)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.feed_quick_dock), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        st.categories.forEach { cat ->
                            FilterChip(
                                selected = cat.path in prefs.quickCategories,
                                onClick = { vm.toggleQuickCategory(cat.path) },
                                label = { Text(cat.name, maxLines = 1) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SwipeTargetRow(
                        label = stringResource(R.string.feed_swipe_left),
                        current = prefs.swipeLeftPath,
                        categories = st.categories,
                        onSelect = { vm.setSwipeLeftCategory(it) },
                    )
                    Spacer(Modifier.height(8.dp))
                    SwipeTargetRow(
                        label = stringResource(R.string.feed_swipe_right),
                        current = prefs.swipeRightPath,
                        categories = st.categories,
                        onSelect = { vm.setSwipeRightCategory(it) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }
}

/**
 * Small circle button for the right rail (icon over a label). Plain
 * Box+clickable: the icon set is deliberately restricted to
 * material-icons-core (Send/Settings/Close/PlayArrow) so no
 * material-icons-extended dependency (and its ~10 MB of unminified
 * weight) enters the APK for two extra glyphs.
 */
@Composable
private fun RailButton(onClick: () -> Unit, icon: @Composable () -> Unit, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp),
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The quick dock: pinned categories as translucent chips; without pins the
 * first six categories fill in (zero-config default). Horizontal scroll
 * when the chips overflow.
 */
@Composable
private fun QuickDock(cats: List<Category>, onSend: (Category) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cats.forEach { cat ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.18f))
                    .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                    .clickable { onSend(cat) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = cat.name,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Thin seek slider with m:ss markers for the CURRENT media page. Polls the
 * shared player every 200 ms; dragging wins over the poll until release.
 */
@Composable
private fun FeedSeekbar(player: Player, visible: Boolean) {
    if (!visible) return
    var posMs by remember { mutableFloatStateOf(0f) }
    var draggingMs by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(player) {
        while (isActive) {
            if (draggingMs == null) {
                posMs = if (player.duration > 0) player.currentPosition.toFloat() else 0f
            }
            delay(200)
        }
    }
    val durMs = if (player.duration > 0) player.duration.toFloat() else 0f
    if (durMs <= 0f) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Slider(
            value = (draggingMs ?: posMs).coerceIn(0f, durMs),
            onValueChange = { draggingMs = it },
            onValueChangeFinished = {
                draggingMs?.let { player.seekTo(it.toLong()) }
                draggingMs = null
            },
            valueRange = 0f..durMs,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = formatMs((draggingMs ?: posMs).toLong()),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatMs(durMs.toLong()),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------------- pages ----------------

/**
 * Current video page: owns the shared player's surface while current.
 * A non-current video page renders the thumbnail-engine poster instead —
 * two PlayerViews must never fight over one player.
 */
@Composable
private fun FeedVideoSurface(
    player: Player,
    isCurrent: Boolean,
    file: SortFile,
    vm: SortViewModel,
    thumbGeneration: Int,
) {
    if (isCurrent) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false } },
            update = { view -> view.player = if (isCurrent) player else null },
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        FeedPoster(file, vm, thumbGeneration)
    }
}

/**
 * Thumbnail-engine poster (video frame / PDF page 1 / archive first image);
 * the gradient type card is the fallback — the exact grid-tile chain.
 */
@Composable
private fun FeedPoster(file: SortFile, vm: SortViewModel, thumbGeneration: Int) {
    val bmpState = produceState<Bitmap?>(null, file.path, thumbGeneration) {
        value = vm.loadThumbnail(file, FEED_POSTER_PX)
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
        TypeCard(file, Modifier.fillMaxSize())
    }
}

/** Audio page: the type card stays visible while the shared player plays (preview audio-mode parity). */
@Composable
private fun FeedAudioCard(file: SortFile) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            TypeCard(file, Modifier.fillMaxWidth(0.8f).height(240.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                text = file.name,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = Paths.fileSizeText(File(file.path).length()),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Image page with pinch zoom (GIFs animate through Coil). While zoomed in
 * the pager's vertical scroll pauses and the swipe-send detector stands
 * down — the zoom wins the gestures until the image is back at 1x.
 */
@Composable
private fun FeedImagePage(file: SortFile, onZoomChange: (Boolean) -> Unit) {
    var scale by remember(file.path) { mutableFloatStateOf(1f) }
    var offset by remember(file.path) { mutableStateOf(Offset.Zero) }
    val transformable = rememberTransformableState { zoomChange, pan, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    LaunchedEffect(scale) { onZoomChange(scale > 1.01f) }
    DisposableEffect(file.path) { onDispose { onZoomChange(false) } }
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
                translationX = offset.x
                translationY = offset.y
            }
            .transformable(transformable),
        loading = { TypeCard(file, Modifier.fillMaxSize()) },
        error = {
            Text(
                text = stringResource(
                    if (isGif) R.string.err_cannot_preview_gif else R.string.err_cannot_preview_image,
                ),
                color = Color.White,
                modifier = Modifier.padding(16.dp),
            ),
        },
    )
}

// ---------------- feed settings pieces ----------------

/**
 * One swipe-target row: label + a dropdown of "Off" plus every category.
 * Path matching rides Paths.samePath so canonical duplicates (//storage
 * vs /storage) resolve to the same entry.
 */
@Composable
private fun SwipeTargetRow(
    label: String,
    current: String?,
    categories: List<Category>,
    onSelect: (String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val currentName = current?.let { c ->
        categories.firstOrNull { Paths.samePath(File(it.path), File(c)) }?.name
    }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            TextButton(onClick = { open = true }) {
                Text(currentName ?: stringResource(R.string.feed_swipe_off))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.feed_swipe_off)) },
                    onClick = {
                        open = false
                        onSelect(null)
                    },
                )
                categories.forEach { cat ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = cat.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            open = false
                            onSelect(cat.path)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Simple undo arrow (curved stroke over the top + solid left-pointing
 * head) drawn with the Canvas API — material-icons-core ships no Undo
 * glyph and material-icons-extended would add ~10 MB of unminified APK
 * weight for one icon.
 */
private fun undoPainter(color: Color): Painter = object : Painter() {
    override val intrinsicSize: Size = Size(24f, 24f)

    override fun DrawScope.onDraw() {
        val stroke = Stroke(width = 2.2f, cap = StrokeCap.Round)
        // From the left point (9 o'clock) clockwise over the top (12) to
        // the right point (3 o'clock).
        drawArc(
            color = color,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(4.5f, 6f),
            size = Size(15f, 15f),
            style = stroke,
        )
        val head = Path().apply {
            moveTo(1.5f, 13.5f)
            lineTo(8f, 9.8f)
            lineTo(8f, 17.2f)
            close()
        }
        drawPath(head, color)
    }
}

/** "m:ss" position text for the seek slider (same format as the preview). */
private fun formatMs(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}
