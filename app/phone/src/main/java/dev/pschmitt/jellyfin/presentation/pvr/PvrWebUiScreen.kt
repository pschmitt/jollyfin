package dev.pschmitt.jellyfin.presentation.pvr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pschmitt.jellyfin.api.pvr.PvrService
import dev.pschmitt.jellyfin.core.R as CoreR
import dev.pschmitt.jellyfin.presentation.components.TopBarTitle
import dev.pschmitt.jellyfin.presentation.theme.spacings
import dev.pschmitt.jellyfin.pvr.PvrWebUiClient
import dev.pschmitt.jellyfin.pvr.PvrWebUiTarget
import dev.pschmitt.jellyfin.pvr.openPvrWebUiInBrowser
import dev.pschmitt.jellyfin.pvr.pvrWebUiHeaders

@get:StringRes
val PvrService.displayName: Int
    get() =
        when (this) {
            PvrService.SONARR -> CoreR.string.integrations_sonarr
            PvrService.RADARR -> CoreR.string.integrations_radarr
            PvrService.SEERR -> CoreR.string.integrations_seerr
        }

@get:DrawableRes
val PvrService.icon: Int
    get() =
        when (this) {
            PvrService.SONARR -> CoreR.drawable.ic_sonarr
            PvrService.RADARR -> CoreR.drawable.ic_radarr
            PvrService.SEERR -> CoreR.drawable.ic_seerr
        }

/**
 * Sonarr's/Radarr's/Seerr's own web UI, embedded (JF-94) - reached either as a navbar tab ([isTab])
 * or from an "Open in ..." action, in which case [target] says which page to open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PvrWebUiScreen(
    service: PvrService,
    target: PvrWebUiTarget,
    isTab: Boolean,
    navigateBack: () -> Unit,
    viewModel: PvrWebUiViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(service, target, isTab) { viewModel.load(service, target, isTab) }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TopBarTitle(
                        text = stringResource(service.displayName),
                        iconRes = service.icon,
                        iconTint = Color.Unspecified,
                    )
                },
                navigationIcon = {
                    if (!isTab) {
                        IconButton(onClick = navigateBack) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_arrow_left),
                                contentDescription = null,
                            )
                        }
                    }
                },
                actions = {
                    if (state.baseUrl != null) {
                        IconButton(onClick = { webView?.reload() }) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_refresh_cw),
                                contentDescription = stringResource(CoreR.string.pvr_web_ui_reload),
                            )
                        }
                        IconButton(
                            onClick = {
                                val url = webView?.url ?: state.initialUrl ?: return@IconButton
                                if (!openPvrWebUiInBrowser(context, service, url)) {
                                    Toast.makeText(
                                            context,
                                            CoreR.string.pvr_web_ui_no_browser,
                                            Toast.LENGTH_SHORT,
                                        )
                                        .show()
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_globe),
                                contentDescription =
                                    stringResource(CoreR.string.pvr_web_ui_open_in_browser),
                            )
                        }
                    }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
            )
        },
        contentWindowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val baseUrl = state.baseUrl
            val initialUrl = state.initialUrl
            when {
                state.isLoading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                baseUrl == null || initialUrl == null ->
                    Text(
                        text =
                            stringResource(
                                CoreR.string.pvr_web_ui_unavailable,
                                stringResource(service.displayName),
                            ),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier =
                            Modifier.align(Alignment.Center)
                                .padding(MaterialTheme.spacings.default),
                    )
                else -> {
                    AndroidView(
                        factory = { viewContext ->
                            createWebView(
                                    context = viewContext,
                                    client =
                                        object : PvrWebUiClient(service, baseUrl) {
                                            override fun onPageStarted(
                                                view: WebView,
                                                url: String?,
                                                favicon: Bitmap?,
                                            ) {
                                                super.onPageStarted(view, url, favicon)
                                                progress = 0
                                            }

                                            override fun doUpdateVisitedHistory(
                                                view: WebView,
                                                url: String?,
                                                isReload: Boolean,
                                            ) {
                                                super.doUpdateVisitedHistory(view, url, isReload)
                                                canGoBack = view.canGoBack()
                                                url?.let {
                                                    viewModel.onUrlVisited(service, isTab, it)
                                                }
                                            }
                                        },
                                    onProgress = { progress = it },
                                )
                                .also {
                                    webView = it
                                    it.loadUrl(initialUrl, pvrWebUiHeaders(service))
                                }
                        },
                        onRelease = {
                            webView = null
                            it.destroy()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (progress in 0..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(
    context: android.content.Context,
    client: PvrWebUiClient,
    onProgress: (Int) -> Unit,
): WebView =
    WebView(context).apply {
        layoutParams =
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        // All three UIs are single-page apps that need JS and local storage to work at all.
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        CookieManager.getInstance().setAcceptCookie(true)
        webViewClient = client
        webChromeClient =
            object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    onProgress(newProgress)
                }
            }
    }
