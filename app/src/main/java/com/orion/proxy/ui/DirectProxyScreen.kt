package com.orion.proxy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.orion.proxy.CustomHotspotConfig
import com.orion.proxy.DirectProxyState
import com.orion.proxy.DirectProxyUiState
import com.orion.proxy.ProxyLifecycleStatus
import com.orion.proxy.R
import com.orion.proxy.WifiBandPreference
import com.orion.proxy.formatCompactBytes
import com.orion.proxy.formatMegabytes
import com.orion.proxy.formatSpeed
import com.orion.proxy.ui.theme.AmberRecommended
import com.orion.proxy.ui.theme.CoralError
import com.orion.proxy.ui.theme.EmeraldActive
import com.orion.proxy.ui.theme.JetBrainsMonoFontFamily
import com.orion.proxy.ui.theme.ObsidianBackground
import com.orion.proxy.ui.theme.ObsidianBorder
import com.orion.proxy.ui.theme.ObsidianHairline
import com.orion.proxy.ui.theme.ObsidianSurface
import com.orion.proxy.ui.theme.ObsidianSurfaceElevated
import com.orion.proxy.ui.theme.PureWhite
import com.orion.proxy.ui.theme.SlateDim
import com.orion.proxy.ui.theme.SlateMuted
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectProxyScreen(
    proxyState: DirectProxyState,
    uiState: DirectProxyUiState,
    snackbarHostState: SnackbarHostState,
    onToggleProxy: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onDismissBatteryBanner: () -> Unit,
    onAddQuickTile: () -> Unit,
    onTogglePasswordVisibility: () -> Unit,
    onCopyValue: (label: String, value: String) -> Unit,
    onOpenHelp: () -> Unit,
    onCloseHelp: () -> Unit,
    onOpenEditCredentials: () -> Unit,
    onCloseEditCredentials: () -> Unit,
    onSaveCustomCredentials: (ssidTag: String, ssidName: String, passphrase: String) -> Unit,
    onResetToAutoCredentials: () -> Unit,
    onSelectWifiBand: (WifiBandPreference) -> Unit
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = ObsidianSurfaceElevated,
                    contentColor = PureWhite,
                    shape = RoundedCornerShape(14.dp)
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TopUtilityBar(
                    status = proxyState.status,
                    onAddQuickTile = onAddQuickTile,
                    onOpenHelp = onOpenHelp,
                    onOpenBatterySettings = onOpenBatterySettings
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Centered App Title & Network Subtitle
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = PureWhite,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when (proxyState.status) {
                        ProxyLifecycleStatus.ACTIVE -> stringResource(
                            R.string.subtitle_active_format,
                            proxyState.proxyIp,
                            proxyState.proxyPort
                        )
                        ProxyLifecycleStatus.STARTING -> stringResource(R.string.subtitle_starting)
                        ProxyLifecycleStatus.INACTIVE,
                        ProxyLifecycleStatus.ERROR -> stringResource(R.string.subtitle_inactive)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = SlateMuted,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Contextual Priority Banner (Permissions / Battery Optimization / Error)
                AnimatedVisibility(
                    visible = !uiState.hasAllPermissions ||
                        (!uiState.isIgnoringBatteryOptimizations && !uiState.batteryBannerDismissed) ||
                        proxyState.errorMessage != null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        when {
                            !uiState.hasAllPermissions -> {
                                PriorityGuidanceCard(
                                    title = stringResource(R.string.card_permissions_title),
                                    badgeText = stringResource(R.string.card_permissions_badge),
                                    badgeColor = AmberRecommended,
                                    body = stringResource(R.string.card_permissions_body),
                                    secondaryButtonLabel = stringResource(R.string.btn_app_settings),
                                    primaryButtonLabel = stringResource(R.string.btn_grant_permissions),
                                    onSecondaryClick = onOpenAppSettings,
                                    onPrimaryClick = onRequestPermissions
                                )
                            }
                            proxyState.errorMessage != null -> {
                                ErrorBannerCard(message = proxyState.errorMessage)
                            }
                            !uiState.isIgnoringBatteryOptimizations && !uiState.batteryBannerDismissed -> {
                                PriorityGuidanceCard(
                                    title = stringResource(R.string.card_battery_title),
                                    badgeText = stringResource(R.string.card_battery_badge),
                                    badgeColor = AmberRecommended,
                                    body = stringResource(R.string.card_battery_body),
                                    secondaryButtonLabel = stringResource(R.string.btn_dismiss),
                                    primaryButtonLabel = stringResource(R.string.btn_battery_settings),
                                    onSecondaryClick = onDismissBatteryBanner,
                                    onPrimaryClick = onOpenBatterySettings
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                    }
                }

                // Hero Ring Gauge (inspired directly by reference screenshot)
                ProxyHeroRing(
                    proxyState = proxyState,
                    uptimeFormatted = uiState.uptimeFormatted,
                    onClick = onToggleProxy
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Primary Start / Stop Toggle Button
                PrimaryToggleAction(
                    status = proxyState.status,
                    onToggle = onToggleProxy
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Credentials Info Card (Band Switcher, SSID & Passphrase with Edit & Copy)
                CredentialsCard(
                    proxyState = proxyState,
                    customConfig = uiState.customConfig,
                    supportsCustomBand = uiState.supportsCustomCredentials,
                    supports5Ghz = uiState.supports5Ghz,
                    showPassphrase = uiState.showPassphrase,
                    onTogglePassphraseVisibility = onTogglePasswordVisibility,
                    onEditCredentials = onOpenEditCredentials,
                    onSelectBand = onSelectWifiBand,
                    onCopySsid = { ssid -> onCopyValue("SSID", ssid) },
                    onCopyPassword = { pass -> onCopyValue("Passphrase", pass) }
                )

                Spacer(modifier = Modifier.height(20.dp))

                // 2x2 Hairline Technical Grid (Proxy IP, Port, Active Connections, Data Transferred)
                TelemetryAndEndpointGrid(
                    proxyState = proxyState,
                    onCopyIp = { onCopyValue("Proxy IP", proxyState.proxyIp) },
                    onCopyPort = { onCopyValue("Proxy Port", proxyState.proxyPort.toString()) }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (uiState.showHelpSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onCloseHelp,
            sheetState = sheetState,
            containerColor = ObsidianSurface,
            contentColor = PureWhite,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            WindowsSetupHelpSheet(
                onAddQuickTile = {
                    onCloseHelp()
                    onAddQuickTile()
                },
                onClose = onCloseHelp
            )
        }
    }

    if (uiState.showCredentialsSheet) {
        val credsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = onCloseEditCredentials,
            sheetState = credsSheetState,
            containerColor = ObsidianSurface,
            contentColor = PureWhite,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            CredentialsConfigSheet(
                initialConfig = uiState.customConfig,
                currentLiveSsid = proxyState.ssid,
                currentLivePassphrase = proxyState.passphrase,
                isProxyRunning = proxyState.isRunning,
                supportsCustomCredentials = uiState.supportsCustomCredentials,
                onSave = onSaveCustomCredentials,
                onUseAuto = onResetToAutoCredentials
            )
        }
    }
}

@Composable
private fun TopUtilityBar(
    status: ProxyLifecycleStatus,
    onAddQuickTile: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenBatterySettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        StatusPillBadge(status = status)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Quick Tile Pill Button
            Surface(
                onClick = onAddQuickTile,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("add_quick_tile_button"),
                shape = CircleShape,
                color = ObsidianSurfaceElevated,
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qs_direct_proxy),
                        contentDescription = stringResource(R.string.cd_quick_tile),
                        tint = PureWhite,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Tile",
                        style = MaterialTheme.typography.labelLarge,
                        color = PureWhite
                    )
                }
            }

            // Help Guide Icon Button
            Surface(
                onClick = onOpenHelp,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("help_guide_button"),
                shape = CircleShape,
                color = ObsidianSurfaceElevated,
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.HelpOutline,
                        contentDescription = stringResource(R.string.cd_help),
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Battery Settings Shortcut Button
            Surface(
                onClick = onOpenBatterySettings,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("battery_shortcut_button"),
                shape = CircleShape,
                color = ObsidianSurfaceElevated,
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.btn_battery_settings),
                        tint = PureWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusPillBadge(status: ProxyLifecycleStatus) {
    val infiniteTransition = rememberInfiniteTransition(label = "status_dot_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val (dotColor, labelText) = when (status) {
        ProxyLifecycleStatus.ACTIVE -> EmeraldActive to stringResource(R.string.status_active)
        ProxyLifecycleStatus.STARTING -> AmberRecommended to stringResource(R.string.status_starting)
        ProxyLifecycleStatus.ERROR -> CoralError to stringResource(R.string.status_error)
        ProxyLifecycleStatus.INACTIVE -> SlateDim to stringResource(R.string.status_inactive)
    }

    val effectiveAlpha = if (status == ProxyLifecycleStatus.ACTIVE || status == ProxyLifecycleStatus.STARTING) {
        pulseAlpha
    } else {
        1f
    }

    Surface(
        modifier = Modifier.testTag("status_badge"),
        shape = CircleShape,
        color = ObsidianSurfaceElevated,
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = effectiveAlpha))
            )
            Text(
                text = labelText,
                style = MaterialTheme.typography.labelMedium,
                color = if (status == ProxyLifecycleStatus.INACTIVE) SlateMuted else PureWhite
            )
        }
    }
}

@Composable
private fun PriorityGuidanceCard(
    title: String,
    badgeText: String,
    badgeColor: Color,
    body: String,
    secondaryButtonLabel: String,
    primaryButtonLabel: String,
    onSecondaryClick: () -> Unit,
    onPrimaryClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("priority_guidance_card"),
        shape = RoundedCornerShape(18.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = PureWhite,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = SlateMuted
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onSecondaryClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("guidance_secondary_button"),
                    shape = CircleShape,
                    border = BorderStroke(1.dp, ObsidianBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = ObsidianBackground,
                        contentColor = PureWhite
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(
                        text = secondaryButtonLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Button(
                    onClick = onPrimaryClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("guidance_primary_button"),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PureWhite,
                        contentColor = ObsidianBackground
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    Text(
                        text = primaryButtonLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorBannerCard(message: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("error_banner_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, CoralError.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = CoralError,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = PureWhite
            )
        }
    }
}

@Composable
private fun ProxyHeroRing(
    proxyState: DirectProxyState,
    uptimeFormatted: String,
    onClick: () -> Unit
) {
    val toggleCd = stringResource(R.string.cd_toggle_proxy)

    val targetSweep = when (proxyState.status) {
        ProxyLifecycleStatus.ACTIVE -> 270f
        ProxyLifecycleStatus.STARTING -> 140f
        ProxyLifecycleStatus.INACTIVE,
        ProxyLifecycleStatus.ERROR -> 0f
    }

    val animatedSweep by animateFloatAsState(
        targetValue = targetSweep,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 220f),
        label = "ring_sweep"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "ring_rotation")
    val startingRotation by infiniteTransition.animateFloat(
        initialValue = -90f,
        targetValue = 270f,
        animationSpec = infiniteRepeatable(
            animation = tween(1300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "starting_arc_angle"
    )

    val startAngle = if (proxyState.status == ProxyLifecycleStatus.STARTING) {
        startingRotation
    } else {
        -90f
    }

    Box(
        modifier = Modifier
            .size(220.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = toggleCd }
            .testTag("hero_status_ring"),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 18.dp.toPx()
            val inset = strokeWidth / 2f + 4.dp.toPx()
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)

            // Base matte charcoal track
            drawArc(
                color = Color(0xFF1A2028),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )

            // Active / starting high-contrast white arc
            if (animatedSweep > 1f) {
                drawArc(
                    color = PureWhite,
                    startAngle = startAngle,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // Signature circular dark endpoint indicator node (mirrors reference gauge)
                val endRad = (startAngle + animatedSweep) * (PI / 180.0)
                val radius = arcSize.width / 2f
                val centerX = size.width / 2f + radius * cos(endRad).toFloat()
                val centerY = size.height / 2f + radius * sin(endRad).toFloat()
                drawCircle(
                    color = ObsidianBackground,
                    radius = strokeWidth * 0.34f,
                    center = Offset(centerX, centerY)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            when (proxyState.status) {
                ProxyLifecycleStatus.ACTIVE -> {
                    Text(
                        text = uptimeFormatted,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontSize = 32.sp
                        ),
                        color = PureWhite
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = formatSpeed(proxyState.throughputBytesPerSec),
                        style = MaterialTheme.typography.titleSmall,
                        color = EmeraldActive
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = proxyState.activeBandLabel?.let { "$it • LIVE" } ?: "BRIDGE LIVE",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMuted
                    )
                }
                ProxyLifecycleStatus.STARTING -> {
                    Icon(
                        imageVector = Icons.Rounded.WifiTethering,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "STARTING",
                        style = MaterialTheme.typography.labelMedium,
                        color = AmberRecommended
                    )
                }
                ProxyLifecycleStatus.INACTIVE,
                ProxyLifecycleStatus.ERROR -> {
                    Icon(
                        imageVector = Icons.Rounded.PowerSettingsNew,
                        contentDescription = null,
                        tint = PureWhite,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "OFFLINE",
                        style = MaterialTheme.typography.labelMedium,
                        color = SlateMuted
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "TAP TO START",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateDim
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryToggleAction(
    status: ProxyLifecycleStatus,
    onToggle: () -> Unit
) {
    val isRunning = status == ProxyLifecycleStatus.ACTIVE || status == ProxyLifecycleStatus.STARTING

    Button(
        onClick = onToggle,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("toggle_proxy_button"),
        shape = CircleShape,
        colors = if (isRunning) {
            ButtonDefaults.buttonColors(
                containerColor = ObsidianSurfaceElevated,
                contentColor = PureWhite
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = PureWhite,
                contentColor = ObsidianBackground
            )
        },
        border = if (isRunning) BorderStroke(1.dp, CoralError.copy(alpha = 0.65f)) else null
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Rounded.Stop else Icons.Rounded.PowerSettingsNew,
            contentDescription = null,
            tint = if (isRunning) CoralError else ObsidianBackground,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = when (status) {
                ProxyLifecycleStatus.ACTIVE -> stringResource(R.string.action_stop_proxy)
                ProxyLifecycleStatus.STARTING -> stringResource(R.string.action_stop_proxy)
                ProxyLifecycleStatus.INACTIVE,
                ProxyLifecycleStatus.ERROR -> stringResource(R.string.action_start_proxy)
            },
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
private fun CredentialsCard(
    proxyState: DirectProxyState,
    customConfig: CustomHotspotConfig,
    supportsCustomBand: Boolean,
    supports5Ghz: Boolean,
    showPassphrase: Boolean,
    onTogglePassphraseVisibility: () -> Unit,
    onEditCredentials: () -> Unit,
    onSelectBand: (WifiBandPreference) -> Unit,
    onCopySsid: (String) -> Unit,
    onCopyPassword: (String) -> Unit
) {
    val hasCustomReady = customConfig.enabled && customConfig.isValid
    val effectiveSsid = when {
        !proxyState.ssid.isNullOrBlank() -> proxyState.ssid
        !proxyState.isRunning && hasCustomReady -> customConfig.fullSsid
        else -> null
    }
    val effectivePassphrase = when {
        !proxyState.passphrase.isNullOrBlank() -> proxyState.passphrase
        !proxyState.isRunning && hasCustomReady -> customConfig.passphrase
        else -> null
    }

    val ssidDisplay = when {
        !effectiveSsid.isNullOrBlank() -> effectiveSsid
        proxyState.isRunning -> stringResource(R.string.value_waiting_group)
        else -> stringResource(R.string.value_not_active)
    }

    val passwordDisplay = when {
        !effectivePassphrase.isNullOrBlank() -> if (showPassphrase) {
            effectivePassphrase
        } else {
            "•".repeat(effectivePassphrase.length.coerceAtLeast(8))
        }
        proxyState.isRunning -> stringResource(R.string.value_waiting_group)
        else -> "••••••••"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("credentials_card"),
        shape = RoundedCornerShape(18.dp),
        color = ObsidianSurface,
        border = BorderStroke(1.dp, ObsidianBorder)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Compact Card Header with Mode Badge & Edit Action
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "WI-FI DIRECT",
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMuted
                    )
                    Surface(
                        shape = CircleShape,
                        color = ObsidianSurfaceElevated,
                        border = BorderStroke(1.dp, ObsidianBorder)
                    ) {
                        val badgeLabel = when {
                            proxyState.operatingFrequencyMhz > 0 ->
                                "${proxyState.activeBandLabel ?: ""} • ${proxyState.operatingFrequencyMhz} MHz"
                            hasCustomReady -> stringResource(R.string.badge_custom_config)
                            else -> stringResource(R.string.badge_auto_config)
                        }
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = if (proxyState.operatingFrequencyMhz > 0 || hasCustomReady) EmeraldActive else SlateMuted,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Surface(
                    onClick = onEditCredentials,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("edit_credentials_button"),
                    shape = CircleShape,
                    color = ObsidianSurfaceElevated,
                    border = BorderStroke(1.dp, ObsidianBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.cd_edit_credentials),
                            tint = PureWhite,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.btn_edit),
                            style = MaterialTheme.typography.labelMedium,
                            color = PureWhite
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 1.dp, color = ObsidianHairline)

            // 2.4 GHz / 5 GHz Bandwidth Switcher
            BandSelectorRow(
                selectedBand = if (supports5Ghz) customConfig.preferredBand else WifiBandPreference.BAND_2GHZ,
                supportsCustomBand = supportsCustomBand,
                supports5Ghz = supports5Ghz,
                onSelectBand = onSelectBand
            )

            HorizontalDivider(thickness = 1.dp, color = ObsidianHairline)

            // SSID Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.label_ssid),
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = ssidDisplay,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = if (!effectiveSsid.isNullOrBlank()) PureWhite else SlateDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("ssid_value_text")
                    )
                }

                IconButton(
                    onClick = { effectiveSsid?.let(onCopySsid) },
                    enabled = !effectiveSsid.isNullOrBlank(),
                    modifier = Modifier.testTag("copy_ssid_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_ssid),
                        tint = if (!effectiveSsid.isNullOrBlank()) PureWhite else SlateDim,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            HorizontalDivider(thickness = 1.dp, color = ObsidianHairline)

            // Passphrase Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.label_password),
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = passwordDisplay,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily
                        ),
                        color = if (!effectivePassphrase.isNullOrBlank()) PureWhite else SlateDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("password_value_text")
                    )
                }

                IconButton(
                    onClick = onTogglePassphraseVisibility,
                    enabled = !effectivePassphrase.isNullOrBlank(),
                    modifier = Modifier.testTag("toggle_password_button")
                ) {
                    Icon(
                        imageVector = if (showPassphrase) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = stringResource(R.string.cd_toggle_password),
                        tint = if (!effectivePassphrase.isNullOrBlank()) SlateMuted else SlateDim,
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = { effectivePassphrase?.let(onCopyPassword) },
                    enabled = !effectivePassphrase.isNullOrBlank(),
                    modifier = Modifier.testTag("copy_password_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_password),
                        tint = if (!effectivePassphrase.isNullOrBlank()) PureWhite else SlateDim,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BandSelectorRow(
    selectedBand: WifiBandPreference,
    supportsCustomBand: Boolean,
    supports5Ghz: Boolean,
    onSelectBand: (WifiBandPreference) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Text(
            text = stringResource(R.string.label_wifi_band),
            style = MaterialTheme.typography.labelSmall,
            color = SlateMuted
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BandPillButton(
                title = stringResource(R.string.band_24_label),
                subtitle = stringResource(R.string.band_24_sub),
                selected = selectedBand == WifiBandPreference.BAND_2GHZ,
                enabled = supportsCustomBand,
                onClick = { onSelectBand(WifiBandPreference.BAND_2GHZ) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("band_24ghz_button")
            )
            BandPillButton(
                title = stringResource(R.string.band_5_label),
                subtitle = stringResource(R.string.band_5_sub),
                selected = selectedBand == WifiBandPreference.BAND_5GHZ,
                enabled = supportsCustomBand && supports5Ghz,
                onClick = { onSelectBand(WifiBandPreference.BAND_5GHZ) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("band_5ghz_button")
            )
        }
        if (!supports5Ghz) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.band_5_unsupported),
                style = MaterialTheme.typography.labelSmall,
                color = SlateDim
            )
        }
    }
}

@Composable
private fun BandPillButton(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = when {
        selected -> PureWhite
        else -> ObsidianBackground
    }
    val titleColor = when {
        selected -> ObsidianBackground
        enabled -> PureWhite
        else -> SlateDim
    }
    val subColor = when {
        selected -> ObsidianBackground.copy(alpha = 0.72f)
        enabled -> SlateMuted
        else -> SlateDim
    }
    val borderStroke = if (selected) null else BorderStroke(1.dp, ObsidianBorder)

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = borderStroke
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = titleColor,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = subColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TelemetryAndEndpointGrid(
    proxyState: DirectProxyState,
    onCopyIp: () -> Unit,
    onCopyPort: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ObsidianHairline, RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(ObsidianBackground)
            .testTag("stats_grid")
    ) {
        // Row 1: Proxy IP | Port
        Row(modifier = Modifier.fillMaxWidth()) {
            GridMetricCell(
                value = proxyState.proxyIp,
                label = stringResource(R.string.label_proxy_ip),
                subCaption = "TAP TO COPY",
                onClick = onCopyIp,
                modifier = Modifier
                    .weight(1f)
                    .testTag("proxy_ip_cell")
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(84.dp)
                    .background(ObsidianHairline)
            )
            GridMetricCell(
                value = proxyState.proxyPort.toString(),
                label = stringResource(R.string.label_proxy_port),
                subCaption = "HTTP / HTTPS",
                onClick = onCopyPort,
                modifier = Modifier
                    .weight(1f)
                    .testTag("proxy_port_cell")
            )
        }

        HorizontalDivider(thickness = 1.dp, color = ObsidianHairline)

        // Row 2: Active Connections | Data Transferred
        Row(modifier = Modifier.fillMaxWidth()) {
            GridMetricCell(
                value = proxyState.activeConnections.toString(),
                label = stringResource(R.string.label_active_connections),
                subCaption = if (proxyState.activeConnections == 1) "ACTIVE SOCKET" else "ACTIVE SOCKETS",
                valueColor = if (proxyState.activeConnections > 0) EmeraldActive else PureWhite,
                onClick = null,
                modifier = Modifier
                    .weight(1f)
                    .testTag("active_connections_cell")
            )
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(84.dp)
                    .background(ObsidianHairline)
            )
            GridMetricCell(
                value = formatMegabytes(proxyState.totalBytes),
                label = stringResource(R.string.label_data_transferred),
                subCaption = stringResource(
                    R.string.label_tx_rx_format,
                    formatCompactBytes(proxyState.txBytes),
                    formatCompactBytes(proxyState.rxBytes)
                ),
                onClick = null,
                modifier = Modifier
                    .weight(1f)
                    .testTag("data_transferred_cell")
            )
        }
    }
}

@Composable
private fun GridMetricCell(
    value: String,
    label: String,
    subCaption: String,
    modifier: Modifier = Modifier,
    valueColor: Color = PureWhite,
    onClick: (() -> Unit)? = null
) {
    val clickableModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }

    Column(
        modifier = clickableModifier
            .height(84.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.SemiBold
            ),
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = SlateMuted,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subCaption,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = SlateDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun WindowsSetupHelpSheet(
    onAddQuickTile: () -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(R.string.help_sheet_title),
            style = MaterialTheme.typography.headlineSmall,
            color = PureWhite
        )

        Spacer(modifier = Modifier.height(16.dp))

        HelpStepItem(
            title = stringResource(R.string.help_step_1_title),
            body = stringResource(R.string.help_step_1_body)
        )
        Spacer(modifier = Modifier.height(12.dp))
        HelpStepItem(
            title = stringResource(R.string.help_step_2_title),
            body = stringResource(R.string.help_step_2_body)
        )
        Spacer(modifier = Modifier.height(12.dp))
        HelpStepItem(
            title = stringResource(R.string.help_step_3_title),
            body = stringResource(R.string.help_step_3_body)
        )

        Spacer(modifier = Modifier.height(18.dp))
        HorizontalDivider(thickness = 1.dp, color = ObsidianHairline)
        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.help_qs_section_title),
            style = MaterialTheme.typography.titleMedium,
            color = PureWhite
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.help_qs_section_body),
            style = MaterialTheme.typography.bodyMedium,
            color = SlateMuted
        )

        Spacer(modifier = Modifier.height(18.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onAddQuickTile,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("sheet_add_qs_tile_button"),
                shape = CircleShape,
                border = BorderStroke(1.dp, ObsidianBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = ObsidianBackground,
                    contentColor = PureWhite
                )
            ) {
                Text(
                    text = stringResource(R.string.btn_add_qs_tile),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Button(
                onClick = onClose,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("sheet_close_button"),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PureWhite,
                    contentColor = ObsidianBackground
                )
            ) {
                Text(
                    text = stringResource(R.string.help_close),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun HelpStepItem(title: String, body: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = PureWhite
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = SlateMuted
        )
    }
}
