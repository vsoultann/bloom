package app.bloom.ui.screens

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.bloom.AppViewModel
import app.bloom.ui.components.*
import app.bloom.ui.theme.IconMode
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion
import org.json.JSONObject

@Composable
fun LoginScreen(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    val spec = LocalLook.current
    val focus = LocalFocusManager.current
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }

    // entrance: content rises in
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val rise by animateFloatAsState(if (shown) 1f else 0f, Motion.spatial(), label = "rise")

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.surface, scheme.surface)),
        ),
    ) {
        BackgroundShapes()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).systemBarsPadding().imePadding()
                .padding(horizontal = 24.dp)
                .graphicsLayer { alpha = rise.coerceIn(0f, 1f); translationY = (1 - rise) * 80f },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(64.dp))
            BloomLogo(112.dp)
            Spacer(Modifier.height(24.dp))
            Text("Welcome to Bloom", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Text(
                "Sign in with your Root account",
                style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                username, { username = it; vm.loginError = null },
                label = { Text("Username or email") },
                leadingIcon = { Icon(Icons.Rounded.Person, null) },
                singleLine = true, enabled = !vm.signingIn,
                shape = RoundedCornerShape(spec.rowRadius + 4.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(2.dp))
            ShapePasswordField(
                value = password,
                onValueChange = { password = it; vm.loginError = null },
                revealed = showPw,
                onToggleReveal = { showPw = !showPw },
                onDone = { focus.clearFocus(); vm.signIn(username, password) },
                enabled = !vm.signingIn,
            )

            AnimatedVisibility(vm.loginError != null, enter = expandVertically(Motion.spatial()) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                Text(
                    vm.loginError ?: "",
                    color = scheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { focus.clearFocus(); vm.signIn(username, password) },
                enabled = !vm.signingIn,
                shape = RoundedCornerShape(if (spec.iconMode == IconMode.LIVING) 50 else 25),
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                AnimatedContent(vm.signingIn, transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) }, label = "btn") { busy ->
                    if (busy) MorphingLoader(size = 26.dp, color = scheme.onPrimary) else Text("Sign in", style = MaterialTheme.typography.titleMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { vm.tryDemo() }, enabled = !vm.signingIn) { Text("Explore with demo data instead") }

            Spacer(Modifier.height(24.dp))
            Text(
                "Bloom is an unofficial client. Your password goes only to Root's own sign-in server; " +
                    "Bloom stores just the session token, in its private storage.",
                style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }

        vm.challengeUrl?.let { url -> TurnstileSheet(url, onToken = vm::submitTurnstile, onCancel = vm::cancelTurnstile) }
    }
}

/**
 * Shows Root's Turnstile page so the user can complete the check themselves.
 * Root's page reports through `window.ReactNativeWebView.postMessage(...)`
 * (it's built for their React Native app), so we expose a bridge with that name.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TurnstileSheet(url: String, onToken: (String) -> Unit, onCancel: (String?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var loading by remember { mutableStateOf(true) }
    BackHandler { onCancel(null) }
    Box(Modifier.fillMaxSize().background(scheme.scrim.copy(alpha = 0.5f)), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = scheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.7f),
        ) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Quick security check", style = MaterialTheme.typography.titleMedium)
                        Text("Root asks for this before signing in", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onCancel(null) }) { Icon(Icons.Rounded.Close, "Cancel") }
                }
                Box(Modifier.fillMaxSize()) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                // Cloudflare's widget runs in a cross-site iframe and needs its cookies.
                                val webView = this
                                android.webkit.CookieManager.getInstance().apply {
                                    setAcceptCookie(true)
                                    setAcceptThirdPartyCookies(webView, true)
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                                        android.util.Log.d("BloomTurnstile", "${m.messageLevel()}: ${m.message()}")
                                        return true
                                    }
                                }
                                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                addJavascriptInterface(object {
                                    @JavascriptInterface
                                    fun postMessage(data: String) {
                                        val msg = runCatching { JSONObject(data) }.getOrNull() ?: return
                                        val token = msg.optString("token")
                                        post {
                                            when (msg.optString("type")) {
                                                "success" -> if (token.isNotBlank()) onToken(token) else onCancel("Verification returned no token")
                                                // Keep the sheet open: Root's page shows its own Retry button.
                                                "error" -> android.util.Log.w("BloomTurnstile", "widget error ${msg.optString("errorCode")}")
                                                "expired" -> android.util.Log.w("BloomTurnstile", "token expired, page will re-verify")
                                            }
                                        }
                                    }
                                }, "ReactNativeWebView")
                                webViewClient = object : WebViewClient() {
                                    override fun onPageFinished(view: WebView?, url: String?) { loading = false }
                                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                        if (request?.isForMainFrame == true) post { onCancel("Couldn't load the security check") }
                                    }
                                }
                                // Root's page needs to know which bridge to report through; "mobile" =
                                // window.ReactNativeWebView.postMessage, which we provide above.
                                loadUrl(if ("bridge=" in url) url else url + (if ('?' in url) "&" else "?") + "bridge=mobile")
                            }
                        },
                    )
                    if (loading) MorphingLoader(Modifier.align(Alignment.Center), size = 48.dp)
                }
            }
        }
    }
}
