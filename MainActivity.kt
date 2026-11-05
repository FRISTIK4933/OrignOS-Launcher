package com.example.originlauncher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)
    private val homeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LauncherApp(resumeTick.intValue, homeTick.intValue) }
    }

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeTick.intValue++ // нажали «Домой» — закрыть библиотеку
    }
}

data class AppItem(val label: String, val pkg: String, val cls: String, val icon: ImageBitmap)

fun loadApps(ctx: Context): List<AppItem> {
    val pm = ctx.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map { ri ->
            AppItem(
                ri.loadLabel(pm).toString(),
                ri.activityInfo.packageName,
                ri.activityInfo.name,
                ri.loadIcon(pm).toBitmap(144, 144).asImageBitmap()
            )
        }
        .sortedBy { it.label.lowercase() }
}

fun pickDock(ctx: Context, apps: List<AppItem>): List<AppItem> {
    val pm = ctx.packageManager
    val intents = listOf(
        Intent(Intent.ACTION_DIAL),
        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")),
        Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")),
        Intent(MediaStore.ACTION_IMAGE_CAPTURE)
    )
    val pkgs = intents.mapNotNull {
        pm.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    }.distinct()
    val dock = pkgs.mapNotNull { p -> apps.firstOrNull { it.pkg == p } }.toMutableList()
    for (a in apps) {
        if (dock.size >= 4) break
        if (a !in dock) dock.add(a)
    }
    return dock.take(4)
}

@Composable
fun LauncherApp(resumeTick: Int, homeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.Default) { loadApps(ctx) } }

    val dock = remember(apps) { pickDock(ctx, apps) }
    val pages = remember(apps) { apps.filter { it !in dock }.chunked(20) }
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })

    val drawer = remember { Animatable(0f) }
    val expand = remember { Animatable(0f) }
    var launching by remember { mutableStateOf<Pair<AppItem, Rect>?>(null) }

    // Вернулись из приложения — окно «схлопывается» обратно в иконку
    LaunchedEffect(resumeTick) {
        if (launching != null) {
            expand.animateTo(0f, spring(dampingRatio = 0.9f, stiffness = 300f))
            launching = null
        }
    }
    LaunchedEffect(homeTick) { if (drawer.value > 0f) drawer.animateTo(0f, spring(0.85f, 400f)) }
    BackHandler(enabled = drawer.value > 0f) { scope.launch { drawer.animateTo(0f, spring(0.85f, 400f)) } }

    fun openApp(app: AppItem, rect: Rect) {
        if (launching != null) return
        launching = app to rect
        scope.launch {
            expand.snapTo(0f)
            val anim = launch { expand.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 320f)) }
            delay(160)
            try {
                val i = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(app.pkg, app.cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val opts = ActivityOptions.makeCustomAnimation(ctx, android.R.anim.fade_in, android.R.anim.fade_out)
                ctx.startActivity(i, opts.toBundle())
            } catch (e: Exception) {
                anim.cancel()
                expand.animateTo(0f)
                launching = null
            }
        }
    }

    fun settle(last: Float) {
        scope.launch {
            val open = (drawer.value > 0.5f && last <= 6f) || (drawer.value > 0.12f && last < -4f)
            drawer.animateTo(if (open) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 380f))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // ---------- ГЛАВНЫЙ ЭКРАН ----------
        val d = drawer.value
        val e = expand.value
        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = 1f - 0.08f * d + 0.06f * e
                    scaleX = s; scaleY = s
                    alpha = (1f - 0.5f * d - 0.6f * e).coerceIn(0f, 1f)
                }
                .blur((24f * d).dp)
                .pointerInput(Unit) {
                    var last = 0f
                    detectVerticalDragGestures(
                        onDragStart = { last = 0f },
                        onDragEnd = { settle(last) },
                        onDragCancel = { settle(0f) },
                        onVerticalDrag = { change, dy ->
                            change.consume(); last = dy
                            scope.launch {
                                drawer.snapTo((drawer.value - dy / (size.height * 0.6f)).coerceIn(0f, 1f))
                            }
                        }
                    )
                }
                .systemBarsPadding()
                .padding(horizontal = 16.dp)
        ) {
            ClockWidget()

            HorizontalPager(pagerState, Modifier.weight(1f)) { page ->
                val offset = { (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction }
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val o = offset()
                            val a = abs(o).coerceIn(0f, 1f)
                            alpha = 1f - 0.7f * a
                            val s = 1f - 0.1f * a
                            scaleX = s; scaleY = s
                            rotationY = o * -16f
                            cameraDistance = 14f * density
                        },
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    (pages.getOrNull(page) ?: emptyList()).chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEachIndexed { col, app ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .graphicsLayer { translationX = offset() * 50f * col },
                                    contentAlignment = Alignment.Center
                                ) { AppIcon(app) { a, r -> openApp(a, r) } }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            Row(
                Modifier.align(Alignment.CenterHorizontally).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                repeat(pages.size) { i ->
                    val sel = pagerState.currentPage == i
                    val w by animateDpAsState(if (sel) 18.dp else 6.dp, spring(0.6f, 400f), label = "dot")
                    Box(
                        Modifier.height(6.dp).width(w).clip(CircleShape)
                            .background(Color.White.copy(alpha = if (sel) 0.95f else 0.45f))
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color.White.copy(alpha = 0.2f))
                    .padding(vertical = 14.dp)
            ) {
                dock.forEach { app ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        AppIcon(app, showLabel = false) { a, r -> openApp(a, r) }
                    }
                }
            }
        }

        // ---------- БИБЛИОТЕКА ПРИЛОЖЕНИЙ ----------
        if (d > 0.001f) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f * d)).pointerInput(Unit) {})
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = d
                        translationY = (1f - d) * size.height * 0.12f
                        val s = 0.94f + 0.06f * d
                        scaleX = s; scaleY = s
                    }
                    .systemBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .pointerInput(Unit) {
                            var last = 0f
                            detectVerticalDragGestures(
                                onDragEnd = { settle(last) },
                                onDragCancel = { settle(0f) },
                                onVerticalDrag = { change, dy ->
                                    change.consume(); last = dy
                                    scope.launch {
                                        drawer.snapTo((drawer.value - dy / (size.height * 12f)).coerceIn(0f, 1f))
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape).background(Color.White.copy(0.6f)))
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(apps) { index, app ->
                        Box(
                            Modifier.graphicsLayer {
                                val s = (d * 1.5f - (index % 4) * 0.08f - (index / 4).coerceAtMost(6) * 0.04f).coerceIn(0f, 1f)
                                scaleX = 0.7f + 0.3f * s; scaleY = scaleX; alpha = s
                            },
                            contentAlignment = Alignment.Center
                        ) { AppIcon(app) { a, r -> openApp(a, r) } }
                    }
                }
            }
        }

        // ---------- ОКНО, РАСКРЫВАЮЩЕЕСЯ ИЗ ИКОНКИ ----------
        launching?.let { (app, r) ->
            val p = expand.value
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val left = lerp(r.left, 0f, p)
                    val top = lerp(r.top, 0f, p)
                    val right = lerp(r.right, size.width, p)
                    val bottom = lerp(r.bottom, size.height, p)
                    drawRoundRect(
                        Color.White.copy(alpha = 0.15f + 0.8f * p),
                        Offset(left, top),
                        Size(right - left, bottom - top),
                        CornerRadius(lerp(r.width * 0.24f, 0f, p))
                    )
                    val iconAlpha = (1f - p * 1.8f).coerceIn(0f, 1f)
                    if (iconAlpha > 0f) {
                        val sz = lerp(r.width, r.width * 2.2f, p)
                        drawImage(
                            app.icon,
                            dstOffset = IntOffset((r.center.x - sz / 2).roundToInt(), (r.center.y - sz / 2).roundToInt()),
                            dstSize = IntSize(sz.roundToInt(), sz.roundToInt()),
                            alpha = iconAlpha
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun AppIcon(app: AppItem, showLabel: Boolean = true, onLaunch: (AppItem, Rect) -> Unit) {
    val currentOnLaunch by rememberUpdatedState(onLaunch)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (pressed) 0.82f else 1f,
        spring(dampingRatio = 0.4f, stiffness = 420f),
        label = "press"
    )
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Column(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .pointerInput(app) {
                detectTapGestures(
                    onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                    onTap = { currentOnLaunch(app, bounds) }
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            bitmap = app.icon,
            contentDescription = app.label,
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .onGloballyPositioned { bounds = it.boundsInRoot() }
        )
        if (showLabel) {
            Spacer(Modifier.height(5.dp))
            BasicText(
                app.label,
                modifier = Modifier.width(72.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center,
                    shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 6f)
                )
            )
        }
    }
}

@Composable
fun ClockWidget() {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 28.dp, bottom = 12.dp)) {
        BasicText(
            timeFmt.format(Date(now)),
            style = TextStyle(color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Light)
        )
        BasicText(
            dateFmt.format(Date(now)),
            style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp)
        )
    }
}
