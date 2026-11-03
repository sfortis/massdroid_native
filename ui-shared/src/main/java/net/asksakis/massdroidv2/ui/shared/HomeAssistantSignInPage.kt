package net.asksakis.massdroidv2.ui.shared

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

private const val TAG = "HaSignInPage"

/**
 * Whether this device can show [HomeAssistantSignInPage]. A TV or a car head unit can ship
 * without a WebView provider, and the sign-in button is then left out.
 */
fun isWebViewAvailable(): Boolean = try {
    WebView.getCurrentWebViewPackage() != null
} catch (e: RuntimeException) {
    Log.w(TAG, "No WebView: ${e.message}")
    false
}

/**
 * The Home Assistant sign-in page, shown inside the app on devices that have no browser (the TV
 * and the car). The page ends on a link with [callbackScheme], which is handed to [onCallback]
 * and never loaded. BACK calls [onCancel], because a half-finished login has nothing to go back
 * to.
 *
 * The app's cookies and web storage are cleared before the page loads and again when it closes,
 * so no Home Assistant session stays in the app and a second server does not sign in with the
 * first one's login. The app has no other web content that this could log out.
 *
 * [errorContent] draws a page that failed to load in the app's own theme, with Retry when
 * `retry` is not null and Cancel, which is the caller's [onCancel].
 */
// The Home Assistant login page is a JavaScript application and does not work without it.
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HomeAssistantSignInPage(
    url: String,
    callbackScheme: String,
    onCallback: (link: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    errorContent: @Composable (message: String, retry: (() -> Unit)?) -> Unit
) {
    val currentOnCallback by rememberUpdatedState(onCallback)
    val currentOnCancel by rememberUpdatedState(onCancel)
    var failure by remember(url) { mutableStateOf<PageFailure?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var lastUrl by remember(url) { mutableStateOf(url) }

    BackHandler { currentOnCancel() }

    // The WebView itself is destroyed by AndroidView's onRelease, for a new url as well.
    DisposableEffect(Unit) {
        onDispose {
            webView = null
            clearWebSession()
        }
    }

    Box(modifier = modifier) {
        // A WebView whose renderer is gone cannot be used again, so it leaves the composition.
        if (failure?.retryable != false) key(url) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                onRelease = { view ->
                    if (webView === view) webView = null
                    (view as? WebView)?.let {
                        it.stopLoading()
                        it.destroy()
                    }
                },
                update = { view ->
                    // Kept out of sight and out of the D-pad's reach while the error shows.
                    view.visibility = if (failure == null) View.VISIBLE else View.INVISIBLE
                },
                factory = { context ->
                    val page = try {
                        WebView(context)
                    } catch (e: RuntimeException) {
                        // The provider can be missing or broken although the check found one.
                        Log.w(TAG, "WebView could not be created: ${e.message}")
                        failure = PageFailure(NO_WEBVIEW_MESSAGE, retryable = false)
                        return@AndroidView View(context)
                    }
                    page.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        setSupportMultipleWindows(false)
                    }
                    // Focusable, so the remote's D-pad moves through the page's fields.
                    page.isFocusable = true
                    page.isFocusableInTouchMode = true
                    page.webViewClient = SignInClient(
                        callbackScheme = callbackScheme,
                        onCallback = { currentOnCallback(it) },
                        onPageStarted = { lastUrl = it },
                        onFailure = { failure = it }
                    )
                    webView = page
                    clearWebSession { if (webView === page) page.loadUrl(url) }
                    page
                }
            )
        }
        failure?.let { shown ->
            val retry: (() -> Unit)? = if (shown.retryable) {
                {
                    failure = null
                    webView?.loadUrl(lastUrl)
                }
            } else {
                null
            }
            errorContent(shown.message, retry)
        }
    }
}

private data class PageFailure(val message: String, val retryable: Boolean)

private class SignInClient(
    private val callbackScheme: String,
    private val onCallback: (String) -> Unit,
    private val onPageStarted: (String) -> Unit,
    private val onFailure: (PageFailure) -> Unit
) : WebViewClient() {
    private var delivered = false
    private var focused = false

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url
        return when (target.scheme?.lowercase()) {
            "http", "https" -> false
            callbackScheme -> {
                // Only the page itself ends the sign-in. A frame inside it cannot spend the one
                // delivery the real callback needs.
                if (request.isForMainFrame) deliver(target.toString())
                true
            }
            // No other app is opened from the sign-in page.
            else -> true
        }
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        onPageStarted(url)
    }

    override fun onPageFinished(view: WebView, url: String) {
        if (!focused) {
            focused = true
            view.requestFocus()
        }
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (!request.isForMainFrame) return
        // A redirect to the callback that did not pass shouldOverrideUrlLoading fails to load
        // as an unknown scheme; it is still the end of the sign-in.
        if (request.url.scheme.equals(callbackScheme, ignoreCase = true)) {
            deliver(request.url.toString())
            return
        }
        onFailure(PageFailure(loadFailedMessage(error.description?.toString()), retryable = true))
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        handler.cancel()
        onFailure(PageFailure(UNTRUSTED_MESSAGE, retryable = true))
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        // Handled, so the app keeps running; this WebView cannot be used again.
        Log.w(TAG, "WebView renderer gone, crash=${detail.didCrash()}")
        onFailure(PageFailure(STOPPED_MESSAGE, retryable = false))
        return true
    }

    private fun deliver(link: String) {
        if (delivered) return
        delivered = true
        onCallback(link)
    }
}

/** Clears the app's cookies and web storage, then runs [then]. */
private fun clearWebSession(then: (() -> Unit)? = null) {
    try {
        WebStorage.getInstance().deleteAllData()
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            then?.invoke()
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Web session not cleared: ${e.message}")
        then?.invoke()
    }
}

private fun loadFailedMessage(reason: String?): String =
    "The Home Assistant sign-in page did not load" + (reason?.let { " ($it)" } ?: "") +
        ". Check that this device can reach Home Assistant."

private const val UNTRUSTED_MESSAGE = "The Home Assistant page's certificate is not trusted on this device."
private const val STOPPED_MESSAGE = "The sign-in page stopped. Cancel and try again."
private const val NO_WEBVIEW_MESSAGE = "This device cannot show the Home Assistant sign-in page."
