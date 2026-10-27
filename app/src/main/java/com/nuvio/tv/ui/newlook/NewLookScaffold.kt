package com.nuvio.tv.ui.newlook

import kotlin.math.roundToInt
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.tv.material3.Text
import com.nuvio.tv.DrawerItem
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalSidebarExpanded
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.navigation.NuvioNavHost
import com.nuvio.tv.ui.navigation.Screen
import com.nuvio.tv.ui.theme.NuvioLayout
import com.nuvio.tv.ui.util.rememberDrawerItemFocusRequesters
import kotlinx.coroutines.launch

/**
 * What the new look's menu tells the screens under it. Home uses it to draw across the sidebar's column, to embed
 * the top tab bar in its page (menu on top), and to know when the hidden menu is showing.
 */
@Stable
class NewLookChrome internal constructor(
    val menuOnTop: Boolean,
    val hideMenu: Boolean,
    /** Start padding the scaffold gives root screens; Home draws across it. */
    val contentInset: Dp,
    private val menuFocusedState: State,
    private val focusTopBarAction: () -> Unit,
    private val topBarContent: @Composable (onDown: () -> Unit, modifier: Modifier) -> Unit,
    private val pageScroll: androidx.compose.runtime.MutableFloatState
) {
    internal class State {
        var pill by mutableStateOf(false)
        var bar by mutableStateOf(false)
        var avatar by mutableStateOf(false)
        val any: Boolean get() = pill || bar || avatar
    }

    val topBarFocused: Boolean get() = menuFocusedState.bar

    /**
     * How far Home's page has scrolled from the top, in px. With the menu on top and showing, the avatar moves up with
     * it, as the tab bar it sits beside is part of the page (it stayed put over the first row's title before).
     */
    fun reportPageScroll(px: Float) {
        pageScroll.floatValue = px
    }

    /** Menu on top: moves focus to the current screen's tab (Up from Home's window). */
    fun focusTopBar() = focusTopBarAction()

    /** Menu on top: the glass tab bar, for Home to place in its page. [onDown] is where Down goes from it. */
    @Composable
    fun TopBar(onDown: () -> Unit, modifier: Modifier = Modifier) = topBarContent(onDown, modifier)
}

val LocalNewLookChrome = staticCompositionLocalOf<NewLookChrome?> { null }

private val PillWidth = 56.dp
private val PillPadding = 8.dp
private val IconSlot = 40.dp
private val IconGap = 22.dp
private val IconSize = 22.dp
internal val SidebarColumn = 96.dp
private val AvatarSize = 36.dp
internal val AvatarX = 30.dp
internal val AvatarY = 12.dp
private val BarHeight = 36.dp
private val BarTop = 12.dp

/**
 * The new look's navigation: a liquid-glass pill of icons on the left (or a glass tab bar on top), the profile
 * avatar at Home's top-left, and the content. Behaves like the classic sidebar: Left from a screen's left edge or
 * Back moves into the menu, Right (or Down from the top bar) goes back, Back again from the menu leaves the app.
 */
@Composable
internal fun NewLookScaffold(
    longPressBackHeld: MutableState<Boolean>,
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    hideMenu: Boolean,
    menuOnTop: Boolean,
    notificationStyle: com.nuvio.tv.data.local.NotificationStyle,
    activeProfileName: String,
    activeProfileColorHex: String,
    activeProfileAvatarImageUrl: String?,
    showProfileSelector: Boolean,
    onSwitchProfile: () -> Unit,
    onNavigate: (String) -> Unit,
    onExitApp: () -> Unit,
    navigateToRoute: (String) -> Unit,
    drawerIcon: @Composable (item: DrawerItem, tint: Color, modifier: Modifier) -> Unit
) {
    val showMenu = currentRoute in rootRoutes
    val onHome = currentRoute == Screen.Home.route
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val contentFocusRequester = remember { FocusRequester() }
    val itemFocus = rememberDrawerItemFocusRequesters(drawerItems)
    val avatarFocus = remember { FocusRequester() }
    val focusState = remember { NewLookChrome.State() }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    // Non-Home screens with the menu on top: the bar is an overlay that shows only while it has focus.
    var overlayBarShown by remember { mutableStateOf(false) }
    val avatarNotify = remember { AvatarNotifyState() }

    val barItems = remember(drawerItems) { topBarOrder(drawerItems) }
    val leftGate = remember { SidebarLeftGate() }
    val pageScroll = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    fun focusAfterLayout(requester: FocusRequester) {
        scope.launch {
            repeat(2) { withFrameNanos { } }
            runCatching { requester.requestFocus() }
        }
    }

    fun selectedOrFirst(): String? =
        selectedDrawerRoute?.takeIf { route -> drawerItems.any { it.route == route } } ?: drawerItems.firstOrNull()?.route

    fun openMenu() {
        val route = selectedOrFirst() ?: return
        if (menuOnTop && !onHome) overlayBarShown = true
        focusAfterLayout(itemFocus.getValue(route))
    }

    fun closeMenu() {
        overlayBarShown = false
        runCatching { contentFocusRequester.requestFocus() }
    }

    fun click(route: String) {
        onNavigate(route)
        navigateToRoute(route)
        overlayBarShown = false
        if (route == currentRoute) closeMenu()
    }

    LaunchedEffect(showMenu) {
        if (!showMenu) overlayBarShown = false
    }
    LaunchedEffect(overlayBarShown, focusState.bar) {
        // The overlay bar goes away once focus has left it.
        if (overlayBarShown && !focusState.bar) {
            repeat(3) { withFrameNanos { } }
            if (!focusState.bar) overlayBarShown = false
        }
    }

    BackHandler(enabled = showMenu && !focusState.any) { openMenu() }
    BackHandler(enabled = showMenu && focusState.any) {
        if (longPressBackHeld.value) return@BackHandler
        onExitApp()
    }

    val topBar: @Composable (() -> Unit, Modifier) -> Unit = { onDown, modifier ->
        GlassTabBar(
            items = barItems,
            selectedRoute = selectedDrawerRoute,
            itemFocus = itemFocus,
            onFocusChanged = { focusState.bar = it },
            onClick = ::click,
            onLeftFromFirst = {
                if (onHome) focusAfterLayout(avatarFocus)
            },
            onDown = onDown,
            drawerIcon = drawerIcon,
            modifier = modifier
        )
    }
    // The chrome is remembered, so it reaches the bar and the menu through the latest lambdas (which see the
    // current route), never the ones from when it was made.
    val latestTopBar by rememberUpdatedState(topBar)
    val latestOpenMenu by rememberUpdatedState(::openMenu)
    // Root screens make room for the sidebar only while it stays on screen; hidden or on top, they use the full
    // width (Genres and Search had a wasted column on the left).
    val sidebarInset = if (!menuOnTop && !hideMenu) NuvioLayout.tokens.sidebarContentOffset else 0.dp
    val chrome = remember(menuOnTop, hideMenu, barItems, selectedDrawerRoute, onHome) {
        NewLookChrome(
            menuOnTop = menuOnTop,
            hideMenu = hideMenu,
            contentInset = sidebarInset,
            menuFocusedState = focusState,
            focusTopBarAction = { latestOpenMenu() },
            topBarContent = { onDown, modifier -> latestTopBar(onDown, modifier) },
            pageScroll = pageScroll
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (longPressBackHeld.value && event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) longPressBackHeld.value = false
                    return@onPreviewKeyEvent true
                }
                false
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (showMenu) sidebarInset else 0.dp)
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionLeft && event.type == KeyEventType.KeyDown) {
                        leftGate.onLeftDown(event.nativeKeyEvent.eventTime, event.nativeKeyEvent.repeatCount)
                    }
                    // Long-press Back on a root screen goes straight to the menu.
                    if (event.key == Key.Back && event.type == KeyEventType.KeyDown && showMenu &&
                        !focusState.any && event.nativeKeyEvent.isLongPress
                    ) {
                        if (!longPressBackHeld.value) {
                            longPressBackHeld.value = true
                            openMenu()
                        }
                        return@onPreviewKeyEvent true
                    }
                    false
                }
                .onKeyEvent { event ->
                    // Left from a screen's left edge moves into the sidebar.
                    if (!menuOnTop && showMenu && !focusState.any && event.type == KeyEventType.KeyDown &&
                        event.key == Key.DirectionLeft
                    ) {
                        // Only a held Left, or a fresh one after a pause, opens the sidebar (SidebarLeftGate): the
                        // sidebar's onEnter asks the gate when focus search heads into it, and so does this when there
                        // was nothing at all to move to. A quick press that found nothing (a lazy list still
                        // scrolling) tries once more a frame later.
                        if (focusManager.moveFocus(FocusDirection.Left)) {
                            if (!focusState.any) leftGate.onMoved()
                        } else if (leftGate.mayOpen()) {
                            openMenu()
                        } else if (event.nativeKeyEvent.repeatCount == 0) {
                            scope.launch {
                                withFrameNanos { }
                                if (!focusState.any && focusManager.moveFocus(FocusDirection.Left) && !focusState.any) leftGate.onMoved()
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides focusState.any,
                LocalContentFocusRequester provides contentFocusRequester,
                LocalNewLookChrome provides chrome
            ) {
                NuvioNavHost(
                    navController = navController,
                    startDestination = startDestination
                )
            }
        }

        if (showMenu && !menuOnTop) {
            val shown = !hideMenu || focusState.any
            val reveal by animateFloatAsState(
                targetValue = if (shown) 1f else 0f,
                animationSpec = tween(240, easing = FastOutSlowInEasing),
                label = "menuReveal"
            )
            if (hideMenu && reveal > 0f) {
                // Hidden menu: a dark scrim behind it while it's in.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(240.dp)
                        .graphicsLayer { alpha = reveal }
                        .background(
                            Brush.horizontalGradient(
                                0f to Color(0xE0060606),
                                0.45f to Color(0x8C060606),
                                1f to Color.Transparent
                            )
                        )
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(SidebarColumn)
                    .graphicsLayer {
                        alpha = reveal
                        translationX = (1f - reveal) * -28.dp.toPx()
                    },
                contentAlignment = Alignment.Center
            ) {
                GlassPill(
                    items = drawerItems,
                    selectedRoute = selectedDrawerRoute,
                    focused = focusState.pill,
                    focusedIndex = focusedIndex,
                    itemFocus = itemFocus,
                    onFocusChanged = { focusState.pill = it },
                    onItemFocused = { focusedIndex = it },
                    onClick = ::click,
                    onUpFromFirst = { if (onHome) focusAfterLayout(avatarFocus) },
                    onRight = ::closeMenu,
                    mayEnterWithLeft = leftGate::mayOpen,
                    drawerIcon = drawerIcon
                )
            }
        }

        if (showMenu && menuOnTop && !onHome && overlayBarShown) {
            // The bar over a non-Home screen, with a scrim from the top.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(Brush.verticalGradient(listOf(Color(0xE0060606), Color.Transparent)))
            )
            Box(modifier = Modifier.fillMaxWidth().padding(top = BarTop), contentAlignment = Alignment.TopCenter) {
                topBar({ closeMenu() }, Modifier)
            }
            LaunchedEffect(Unit) { openMenu() }
        }

        // Notifications: drawn under the avatar so style 2 grows out from behind it. Over the player only the
        // player's own ones show, always as the island (the avatar isn't drawn there); the rest wait for it to close.
        if (currentRoute?.startsWith("player") != true) {
            // Auto quality's notices belong to the player alone.
            NotificationHost(
                style = notificationStyle,
                avatar = avatarNotify,
                shows = {
                    it.kind != NewLookNotifications.Kind.QUALITY_UP && it.kind != NewLookNotifications.Kind.QUALITY_DOWN
                }
            )
        } else {
            NotificationHost(
                style = com.nuvio.tv.data.local.NotificationStyle.ISLAND,
                avatar = avatarNotify,
                shows = { it.kind.forPlayer }
            )
        }

        if ((showMenu && onHome) || avatarNotify.visible) {
            val avatarShown = (showMenu && onHome && (!hideMenu || focusState.any)) || avatarNotify.visible
            val avatarReveal by animateFloatAsState(
                targetValue = if (avatarShown) 1f else 0f,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                label = "avatarReveal"
            )
            // Menu on top and showing: the avatar scrolls away with the page and its tab bar.
            val scrollsWithPage = menuOnTop && !hideMenu && showMenu && onHome
            val lift by animateFloatAsState(
                targetValue = if (avatarNotify.lifted) 1f else 0f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                label = "avatarLift"
            )
            ProfileBubble(
                name = activeProfileName,
                colorHex = activeProfileColorHex,
                avatarImageUrl = activeProfileAvatarImageUrl,
                focusRequester = avatarFocus,
                canFocus = focusState.any && showMenu && onHome,
                onFocusChanged = { focusState.avatar = it },
                onClick = { if (showProfileSelector) onSwitchProfile() },
                onBackToMenu = {
                    val route = if (menuOnTop) barItems.firstOrNull()?.route else drawerItems.firstOrNull()?.route
                    route?.let { focusAfterLayout(itemFocus.getValue(it)) }
                },
                modifier = Modifier
                    .offset { IntOffset(AvatarX.roundToPx(), AvatarY.roundToPx()) }
                    .graphicsLayer {
                        alpha = avatarReveal
                        val s = (0.82f + 0.18f * avatarReveal) * (1f + 0.08f * lift)
                        scaleX = s
                        scaleY = s
                        translationX = 6.dp.toPx() * lift
                        // A notification brings it back into view wherever the page is.
                        if (scrollsWithPage && !avatarNotify.visible) translationY = -pageScroll.floatValue
                    }
            )
        }
    }
}

/** Menu on top follows Netflix: Search first, the screens as words, Settings last. */
private fun topBarOrder(items: List<DrawerItem>): List<DrawerItem> {
    val search = items.filter { it.route == Screen.Search.route }
    val settings = items.filter { it.route == Screen.Settings.route }
    return search + items.filter { it !in search && it !in settings } + settings
}

// ---------------------------------------------------------------------------------------------
// The pill
// ---------------------------------------------------------------------------------------------

@Composable
internal fun GlassPill(
    items: List<DrawerItem>,
    selectedRoute: String?,
    focused: Boolean,
    focusedIndex: Int,
    itemFocus: Map<String, FocusRequester>,
    onFocusChanged: (Boolean) -> Unit,
    onItemFocused: (Int) -> Unit,
    onClick: (String) -> Unit,
    onUpFromFirst: () -> Unit,
    onRight: () -> Unit,
    /** Asked when Left from a screen heads into the pill; false keeps focus where it is (see SidebarLeftGate). */
    mayEnterWithLeft: () -> Boolean = { true },
    drawerIcon: @Composable (item: DrawerItem, tint: Color, modifier: Modifier) -> Unit
) {
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val at = if (focused && focusedIndex in items.indices) focusedIndex else selectedIndex
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    Box(
        modifier = Modifier
            .width(PillWidth)
            .menuFrost(shape)
            .glassSurface(shape, glints = false)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .focusProperties {
                onEnter = {
                    if (requestedFocusDirection == FocusDirection.Left) {
                        if (!mayEnterWithLeft()) {
                            cancelFocusChange()
                        } else {
                            items.getOrNull(selectedIndex)?.let { runCatching { itemFocus.getValue(it.route).requestFocus() } }
                        }
                    }
                }
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> {
                        onRight()
                        true
                    }
                    Key.DirectionLeft -> true
                    Key.DirectionUp -> {
                        if (focusedIndex <= 0) onUpFromFirst()
                        else items.getOrNull(focusedIndex - 1)?.let { runCatching { itemFocus.getValue(it.route).requestFocus() } }
                        true
                    }
                    Key.DirectionDown -> {
                        items.getOrNull(focusedIndex + 1)?.let { runCatching { itemFocus.getValue(it.route).requestFocus() } }
                        true
                    }
                    else -> false
                }
            }
            .padding(PillPadding)
    ) {
        PillLens(at = at, focused = focused)
        Column(verticalArrangement = Arrangement.spacedBy(IconGap)) {
            items.forEachIndexed { index, item ->
                key(item.route) {
                    var itemFocused by remember { mutableStateOf(false) }
                    val selected = item.route == selectedRoute
                    val tint by androidx.compose.animation.animateColorAsState(
                        targetValue = when {
                            itemFocused -> Color.White
                            selected -> Color(0xFFE6E6E6)
                            else -> Color(0xFF9E9E9E)
                        },
                        animationSpec = tween(200),
                        label = "pillIconTint"
                    )
                    Box(
                        modifier = Modifier
                            .size(IconSlot)
                            .focusRequester(itemFocus.getValue(item.route))
                            .onFocusChanged {
                                itemFocused = it.isFocused
                                if (it.isFocused) onItemFocused(index)
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onClick(item.route) },
                        contentAlignment = Alignment.Center
                    ) {
                        drawerIcon(item, tint, Modifier.size(IconSize))
                    }
                }
            }
        }
    }
}

/**
 * The glass lens behind the icons. At rest it sits faintly on the current screen's icon; with the menu focused it
 * brightens, swells past the pill's sides and flows to the focused icon, stretching toward where it's going first.
 */
@Composable
private fun PillLens(at: Int, focused: Boolean) {
    val density = LocalDensity.current
    val step = with(density) { (IconSlot + IconGap).toPx() }
    val y by animateFloatAsState(
        targetValue = at * step,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 320f),
        label = "lensY"
    )
    val grow by animateFloatAsState(if (focused) 1.14f else 1f, spring(dampingRatio = 0.6f, stiffness = 400f), label = "lensGrow")
    val alpha by animateFloatAsState(if (focused) 1f else 0.55f, tween(200), label = "lensAlpha")
    val stretchX = remember { Animatable(1f) }
    val stretchY = remember { Animatable(1f) }
    var lastAt by remember { mutableIntStateOf(at) }
    var originY by remember { mutableStateOf(0.5f) }
    LaunchedEffect(at) {
        if (at == lastAt) return@LaunchedEffect
        originY = if (at < lastAt) 1f else 0f
        lastAt = at
        launch {
            stretchX.animateTo(0.88f, tween(110))
            stretchX.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f))
        }
        stretchY.animateTo(1.3f, tween(110))
        originY = 0.5f
        stretchY.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 420f))
    }
    Box(
        modifier = Modifier
            // 48 wide in the pill's 40-wide content: requiredSize keeps it a full circle, centred on the icon (size
            // was squeezed to 40, an oval sitting 4 dp left of the icon); only the vertical nudge is needed.
            .offset(y = (-4).dp)
            // Moved by layout, not the layer's translation: a translated layer here was drawn clipped to where it
            // started (the top bar's lens, in the JVM preview), so it showed as a stub off the first item.
            .offset { IntOffset(0, y.roundToInt()) }
            .graphicsLayer {
                this.alpha = alpha
                scaleX = grow * stretchX.value
                scaleY = grow * stretchY.value
                transformOrigin = TransformOrigin(0.5f, originY)
            }
            .requiredSize(48.dp)
            .glassLens(androidx.compose.foundation.shape.CircleShape)
    )
}

// ---------------------------------------------------------------------------------------------
// The top bar
// ---------------------------------------------------------------------------------------------

@Composable
internal fun GlassTabBar(
    items: List<DrawerItem>,
    selectedRoute: String?,
    itemFocus: Map<String, FocusRequester>,
    onFocusChanged: (Boolean) -> Unit,
    onClick: (String) -> Unit,
    onLeftFromFirst: () -> Unit,
    onDown: () -> Unit,
    drawerIcon: @Composable (item: DrawerItem, tint: Color, modifier: Modifier) -> Unit,
    modifier: Modifier = Modifier
) {
    var hasFocus by remember { mutableStateOf(false) }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val selectedIndex = items.indexOfFirst { it.route == selectedRoute }.coerceAtLeast(0)
    val at = if (hasFocus && focusedIndex in items.indices) focusedIndex else selectedIndex
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(19.dp)
    Box(
        modifier = modifier
            .height(BarHeight)
            .menuFrost(shape)
            .glassSurface(shape)
            .onFocusChanged {
                hasFocus = it.hasFocus
                onFocusChanged(it.hasFocus)
            }
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown -> {
                        onDown()
                        true
                    }
                    Key.DirectionUp -> true
                    Key.DirectionLeft -> {
                        if (focusedIndex <= 0) onLeftFromFirst()
                        else items.getOrNull(focusedIndex - 1)?.let { runCatching { itemFocus.getValue(it.route).requestFocus() } }
                        true
                    }
                    Key.DirectionRight -> {
                        items.getOrNull(focusedIndex + 1)?.let { runCatching { itemFocus.getValue(it.route).requestFocus() } }
                        true
                    }
                    else -> false
                }
            }
            .padding(3.dp)
    ) {
        val target = bounds[at]
        if (target != null) {
            val x by animateFloatAsState(target.first, spring(dampingRatio = 0.62f, stiffness = 320f), label = "barLensX")
            val w by animateFloatAsState(target.second, spring(dampingRatio = 0.62f, stiffness = 320f), label = "barLensW")
            val alpha by animateFloatAsState(if (hasFocus) 1f else 0.55f, tween(200), label = "barLensAlpha")
            val density = LocalDensity.current
            Box(
                modifier = Modifier
                    // Moved by layout, not the layer's translation (see PillLens).
                    .offset { IntOffset(x.roundToInt(), 0) }
                    .graphicsLayer {
                        this.alpha = alpha
                        val s = if (hasFocus) 1.06f else 1f
                        scaleX = s
                        scaleY = if (hasFocus) 1.14f else 1f
                    }
                    .width(with(density) { w.toDp() })
                    .height(30.dp)
                    .glassLens(androidx.compose.foundation.shape.RoundedCornerShape(15.dp))
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            items.forEachIndexed { index, item ->
                key(item.route) {
                    var itemFocused by remember { mutableStateOf(false) }
                    val selected = item.route == selectedRoute
                    val color = when {
                        itemFocused -> Color.White
                        selected -> Color(0xFFE6E6E6)
                        else -> Color(0xFF9E9E9E)
                    }
                    val iconOnly = item.route == Screen.Search.route || item.route == Screen.Settings.route
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .widthIn(min = 32.dp)
                            .onGloballyPositioned { bounds[index] = it.positionInParent().x to it.size.width.toFloat() }
                            .focusRequester(itemFocus.getValue(item.route))
                            .onFocusChanged {
                                itemFocused = it.isFocused
                                if (it.isFocused) focusedIndex = index
                            }
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { onClick(item.route) }
                            .padding(horizontal = if (iconOnly) 9.dp else 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (iconOnly) {
                            drawerIcon(item, color, Modifier.size(16.dp))
                        } else {
                            Text(text = item.label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------------------------

/** The active profile's avatar at Home's top-left, outside the menu. OK opens the profile picker. */
@Composable
internal fun ProfileBubble(
    name: String,
    colorHex: String,
    avatarImageUrl: String?,
    focusRequester: FocusRequester,
    canFocus: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
    onBackToMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(200), label = "avatarScale")
    val ring = focusRingColor
    Box(
        modifier = modifier
            .size(AvatarSize)
            .graphicsLayer {
                scaleX *= scale
                scaleY *= scale
            }
            .focusRequester(focusRequester)
            .focusProperties { this.canFocus = canFocus }
            .onFocusChanged {
                focused = it.isFocused
                onFocusChanged(it.isFocused)
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionDown, Key.DirectionRight -> {
                        onBackToMenu()
                        true
                    }
                    Key.DirectionUp, Key.DirectionLeft -> true
                    else -> false
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        ProfileAvatarCircle(
            name = name,
            colorHex = colorHex,
            size = AvatarSize,
            avatarImageUrl = avatarImageUrl,
            imageCrossfade = false,
            // A 1 px ring (the Appearance colour) a little outside it while focused.
            modifier = Modifier.drawWithContent {
                drawContent()
                if (focused) {
                    drawCircle(
                        color = ring,
                        radius = size.minDimension / 2f + 3.dp.toPx(),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
                    )
                }
            }
        )
    }
}
