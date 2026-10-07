package dev.pschmitt.jellyfin.pvr

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Browser
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.pschmitt.jellyfin.api.pvr.PvrAdvancedConfig
import dev.pschmitt.jellyfin.api.pvr.PvrAdvancedSettings
import dev.pschmitt.jellyfin.api.pvr.PvrService
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import timber.log.Timber

/**
 * Headers every request to [service]'s web UI should carry: Basic auth (when configured) plus the
 * profile's custom HTTP headers, applied last so an explicit `Authorization` header wins - same
 * precedence as `PvrHttpClient`'s API interceptor. Read fresh on every call, so a settings change
 * applies to the next request.
 */
fun pvrWebUiHeaders(service: PvrService): Map<String, String> {
    val advanced: PvrAdvancedConfig = PvrAdvancedSettings.provider(service)
    val headers = linkedMapOf<String, String>()
    val username = advanced.basicAuthUsername
    val password = advanced.basicAuthPassword
    if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
        headers["Authorization"] = Credentials.basic(username, password)
    }
    advanced.headers.forEach { (name, value) ->
        headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
        headers[name] = value
    }
    return headers
}

/**
 * Opens [url] in the user's browser, passing [service]'s custom headers via
 * [Browser.EXTRA_HEADERS]. Best effort: most browsers (Chrome included) only honor CORS-safelisted
 * headers there and silently drop the rest, and Basic auth is left to the browser's own prompt
 * rather than putting credentials into the URL.
 */
fun openPvrWebUiInBrowser(context: Context, service: PvrService, url: String): Boolean {
    val extraHeaders =
        Bundle().apply {
            PvrAdvancedSettings.provider(service).headers.forEach { (name, value) ->
                putString(name, value)
            }
        }
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .putExtra(Browser.EXTRA_HEADERS, extraHeaders)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Timber.w(e, "No browser to open %s", url)
        false
    }
}

/**
 * [WebViewClient] for the in-app Sonarr/Radarr/Seerr web UI (JF-94) that makes the service's custom
 * headers / Basic auth stick to every request, not just the initial `loadUrl()` - which is all
 * `WebView` itself supports:
 * - Same-origin GET requests (page loads, scripts, styles, images, API reads) are re-issued through
 *   OkHttp with the headers added, keeping cookies in sync with [CookieManager] in both directions.
 * - WebView never exposes request bodies, so other methods can't be re-issued; instead the HTML of
 *   every main-frame page gets a tiny script injected that adds the same headers to same-origin
 *   `fetch()`/`XMLHttpRequest` calls (which is how all three UIs talk to their API). Native form
 *   POSTs (e.g. Sonarr's/Radarr's forms login) go out without them.
 * - Basic-auth challenges are answered from the stored credentials.
 *
 * Without any custom headers or Basic auth configured, nothing is intercepted. Links leaving the
 * service's origin are handed to the system instead of navigating the embedded view away.
 */
open class PvrWebUiClient(private val service: PvrService, baseUrl: String) : WebViewClient() {
    private val origin: HttpUrl? = baseUrl.toHttpUrlOrNull()

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        if (!request.method.equals("GET", ignoreCase = true)) return null
        val url = request.url.toString().toHttpUrlOrNull() ?: return null
        if (!isSameOrigin(url)) return null
        val extraHeaders = pvrWebUiHeaders(service)
        if (extraHeaders.isEmpty()) return null
        return try {
            fetch(request, url, extraHeaders)
        } catch (e: Exception) {
            // Falling back to WebView's own (header-less) request is no worse than not
            // intercepting at all - and the page shows the real network error instead of a blank.
            Timber.w(e, "Web UI request to %s failed, letting WebView retry it", url)
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.scheme !in setOf("http", "https")) return openExternally(view, url)
        val httpUrl = url.toString().toHttpUrlOrNull() ?: return false
        return if (isSameOrigin(httpUrl)) false else openExternally(view, url)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String?,
    ) {
        val advanced = PvrAdvancedSettings.provider(service)
        val username = advanced.basicAuthUsername
        val password = advanced.basicAuthPassword
        if (host == origin?.host && !username.isNullOrBlank() && !password.isNullOrBlank()) {
            handler.proceed(username, password)
        } else {
            super.onReceivedHttpAuthRequest(view, handler, host, realm)
        }
    }

    private fun openExternally(view: WebView, url: Uri): Boolean {
        try {
            view.context.startActivity(
                Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app to open %s", url)
        }
        return true
    }

    private fun isSameOrigin(url: HttpUrl): Boolean =
        origin != null &&
            url.scheme == origin.scheme &&
            url.host == origin.host &&
            url.port == origin.port

    private fun fetch(
        request: WebResourceRequest,
        url: HttpUrl,
        extraHeaders: Map<String, String>,
    ): WebResourceResponse? {
        val builder = Request.Builder().url(url).get()
        request.requestHeaders.forEach { (name, value) ->
            if (name.lowercase() !in droppedRequestHeaders) builder.header(name, value)
        }
        val cookieManager = CookieManager.getInstance()
        cookieManager.getCookie(url.toString())?.let { builder.header("Cookie", it) }
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }

        val mainFrame = request.isForMainFrame
        val response =
            (if (mainFrame) noRedirectClient else client).newCall(builder.build()).execute()
        response.use {
            response.headers("Set-Cookie").forEach { cookieManager.setCookie(url.toString(), it) }
            if (response.isRedirect) {
                // WebResourceResponse can't carry a 3xx, so a main-frame redirect is replayed as a
                // page that navigates on to the target - a real navigation, so WebView's address
                // and relative-URL base stay right.
                val location = response.header("Location")?.let(url::resolve) ?: return null
                return htmlResponse(
                    "<script>location.replace(${JSONObject.quote(location.toString())})</script>"
                )
            }
            val body = response.body
            val contentType = body.contentType()
            val mimeType =
                contentType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream"
            if (mainFrame && mimeType == "text/html") {
                return htmlResponse(
                    injectHeaderShim(body.string(), extraHeaders),
                    status = response,
                    headers = responseHeaders(response, stripCsp = true),
                )
            }
            return WebResourceResponse(
                mimeType,
                contentType?.charset()?.name(),
                response.code,
                response.message.ifBlank { "OK" },
                responseHeaders(response, stripCsp = false),
                ByteArrayInputStream(body.bytes()),
            )
        }
    }

    private fun htmlResponse(
        html: String,
        status: Response? = null,
        headers: Map<String, String> = emptyMap(),
    ) =
        WebResourceResponse(
            "text/html",
            "UTF-8",
            status?.code ?: 200,
            status?.message?.ifBlank { null } ?: "OK",
            headers,
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )

    /**
     * The response's headers minus what no longer applies to the body we hand WebView (OkHttp
     * already decompressed it, and cookies were already stored). A page we inject a script into
     * also loses its Content-Security-Policy, which would otherwise block that inline script.
     */
    private fun responseHeaders(response: Response, stripCsp: Boolean): Map<String, String> =
        response.headers
            .toMultimap()
            .filterKeys { name ->
                val lower = name.lowercase()
                lower !in droppedResponseHeaders &&
                    !(stripCsp && lower.startsWith("content-security-policy"))
            }
            .mapValues { (_, values) -> values.joinToString(", ") }

    private fun injectHeaderShim(html: String, headers: Map<String, String>): String {
        val script = "<script>${headerShim(JSONObject(headers).toString())}</script>"
        val head = headTag.find(html) ?: return script + html
        return html.substring(0, head.range.last + 1) + script + html.substring(head.range.last + 1)
    }

    private companion object {
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
        private val noRedirectClient: OkHttpClient by lazy {
            client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        }

        // Accept-Encoding: OkHttp only decompresses transparently when it set the header itself.
        // Conditional headers: a 304 can't be handed back through WebResourceResponse.
        private val droppedRequestHeaders =
            setOf("accept-encoding", "cookie", "if-none-match", "if-modified-since", "range")
        private val droppedResponseHeaders =
            setOf("content-encoding", "content-length", "transfer-encoding", "set-cookie")

        private val headTag = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)

        private fun headerShim(headersJson: String) =
            """
            (function () {
              var headers = $headersJson;
              var origin = location.origin;
              function sameOrigin(url) {
                try { return new URL(url, location.href).origin === origin; } catch (e) { return false; }
              }
              var originalFetch = window.fetch;
              if (originalFetch) {
                window.fetch = function (input, init) {
                  try {
                    var url = input && input.url ? input.url : String(input);
                    if (sameOrigin(url)) {
                      init = Object.assign({}, init);
                      var merged = new Headers(init.headers || (input instanceof Request ? input.headers : undefined));
                      for (var name in headers) merged.set(name, headers[name]);
                      init.headers = merged;
                    }
                  } catch (e) {}
                  return originalFetch.call(this, input, init);
                };
              }
              var open = XMLHttpRequest.prototype.open;
              var send = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.open = function (method, url) {
                this.__jollyfinSameOrigin = sameOrigin(url);
                return open.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function () {
                if (this.__jollyfinSameOrigin) {
                  for (var name in headers) {
                    try { this.setRequestHeader(name, headers[name]); } catch (e) {}
                  }
                }
                return send.apply(this, arguments);
              };
            })();
            """
                .trimIndent()
    }
}
