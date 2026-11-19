package app.cadence.feature.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.cadence.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.cadence.LocalContainer
import app.cadence.backend.api.AuthError
import app.cadence.backend.api.OtpChallenge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First-run sign-in: email -> 6-digit one-time code. All rules (expiry, attempts, cooldown) are the
 * backend's job; this screen only collects input, shows state and reports errors.
 */
@Composable
fun AuthFlow() {
    SecureWindow()
    val auth = LocalContainer.current.backend.auth
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    // Not saveable on purpose: a password shouldn't outlive the screen in saved instance state.
    var password by remember { mutableStateOf("") }
    var challenge by remember { mutableStateOf<OtpChallenge?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // A session file exported from another install (Settings, Session) signs this one in. This is the
    // single supported import: it carries the full provisioning, not just a pasted token.
    val context = androidx.compose.ui.platform.LocalContext.current
    val sessionFile = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.cadence.backend.engine.SessionTransfer.import(context, uri) }
            r.onSuccess { app.cadence.feature.settings.restartApp(context) }.onFailure { error = "Could not import the session file: ${it.message}" }
        }
    }
    val importSession = { sessionFile.launch(arrayOf("application/zip", "application/octet-stream")) }

    fun send() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            auth.requestCode(email, password).onSuccess { challenge = it; password = "" }.onFailure { error = it.message }
            busy = false
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        AnimatedContent(
            targetState = challenge,
            transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            contentKey = { it?.challengeId },
            label = "auth",
        ) { ch ->
            if (ch == null) EmailStep(
                email, { email = it; error = null }, password, { password = it; error = null },
                busy, error, ::send, onImport = importSession,
            )
            else CodeStep(
                challenge = ch,
                onBack = { challenge = null; error = null },
                onResent = { challenge = it },
                onImport = importSession,
            )
        }
    }
}

/**
 * Keeps the window out of screenshots, screen recordings and the recents thumbnail while the sign-in
 * screen (email, password, one-time code) is showing.
 */
@Composable
private fun SecureWindow() {
    val window = androidx.compose.ui.platform.LocalContext.current.activity()?.window ?: return
    androidx.compose.runtime.DisposableEffect(window) {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun android.content.Context.activity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.activity()
    else -> null
}

@Composable
private fun AuthLayout(top: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(56.dp)) { top() }
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            content()
        }
    }
}

/** Brand mark: a slowly turning scalloped shape, the same expressive motif as the play button. */
@Composable
private fun Mark(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val spin by rememberInfiniteTransition(label = "mark").animateFloat(0f, 360f, infiniteRepeatable(tween(20_000, easing = LinearEasing)), label = "s")
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(112.dp).graphicsLayer { rotationZ = spin }
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialShapes.Cookie9Sided.toShape()),
        )
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun EmailStep(
    email: String, onEmail: (String) -> Unit, password: String, onPassword: (String) -> Unit,
    busy: Boolean, error: String?, onContinue: () -> Unit, onImport: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    var showPassword by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AuthLayout {
        Spacer(Modifier.height(24.dp))
        Mark(Icons.Rounded.Mail)
        Text(stringResource(R.string.auth_welcome), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 28.dp))
        Text(
            stringResource(R.string.auth_subtitle),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )
        OutlinedTextField(
            value = email,
            onValueChange = onEmail,
            label = { Text(stringResource(R.string.auth_email)) },
            singleLine = true,
            isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentType = ContentType.EmailAddress },
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text(stringResource(R.string.auth_password)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showPassword) stringResource(R.string.auth_hide_password) else stringResource(R.string.auth_show_password))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onContinue() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus).semantics { contentType = ContentType.Password },
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onContinue,
            enabled = email.isNotBlank() && password.isNotEmpty() && !busy,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) LoadingIndicator(Modifier.size(28.dp), color = MaterialTheme.colorScheme.onPrimary)
            else Text(stringResource(R.string.action_continue), style = MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.padding(top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "  " + stringResource(R.string.auth_codes_expire),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onImport, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.auth_import_session))
        }
    }
}

@Composable
private fun CodeStep(
    challenge: OtpChallenge,
    onBack: () -> Unit,
    onResent: (OtpChallenge) -> Unit,
    onImport: () -> Unit,
) {
    val auth = LocalContainer.current.backend.auth
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val keyboard = LocalSoftwareKeyboardController.current
    var code by remember(challenge.challengeId) { mutableStateOf(TextFieldValue("")) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var locked by remember(challenge.challengeId) { mutableStateOf(false) }
    var retryAvailable by remember { mutableStateOf(false) }
    val shake = remember { Animatable(0f) }
    val codeFocus = remember { FocusRequester() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(challenge) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    BackHandler(onBack = onBack)

    fun verify(value: String) {
        if (busy || value.length != challenge.codeLength || locked) return
        busy = true; error = null; retryAvailable = false
        keyboard?.hide()
        scope.launch {
            auth.verifyCode(challenge.challengeId, value)
                .onSuccess { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
                .onFailure { e ->
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    if (e is AuthError.SignInIncomplete) {
                        // The code was accepted; asking for it again would always
                        // fail. Keep it and offer the token-exchange retry instead.
                        error = e.message
                        retryAvailable = true
                        busy = false
                        return@onFailure
                    }
                    retryAvailable = false
                    error = when (e) {
                        is AuthError.WrongCode -> context.resources.getQuantityString(R.plurals.auth_wrong_code, e.attemptsLeft, e.attemptsLeft)
                        is AuthError.TooManyAttempts, is AuthError.Expired -> { locked = true; e.message }
                        else -> e.message
                    }
                    code = TextFieldValue("")
                    busy = false
                    // Straight back to typing: refocus and reopen the keyboard (unless the code is dead).
                    if (!locked) { delay(50); codeFocus.requestFocus(); keyboard?.show() }
                    shake.animateTo(0f, keyframes { durationMillis = 420; -18f at 60; 16f at 140; -10f at 220; 6f at 300; 0f at 420 })
                }
            busy = false
        }
    }

    fun retry() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            auth.resumeSession()
                .onSuccess { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
                .onFailure { e ->
                    haptics.performHapticFeedback(HapticFeedbackType.Reject)
                    error = e.message
                    retryAvailable = e is AuthError.SignInIncomplete
                }
            busy = false
        }
    }

    fun resend() {
        if (busy) return
        busy = true; error = null
        scope.launch {
            auth.resendCode(challenge.challengeId).onSuccess { onResent(it); locked = false }.onFailure { error = it.message }
            busy = false
        }
    }

    AuthLayout(top = { IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.auth_different_email)) } }) {
        Mark(Icons.Rounded.Lock)
        Text(stringResource(R.string.auth_enter_code), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 28.dp))
        Text(
            stringResource(R.string.auth_code_sent, challenge.codeLength, challenge.maskedDestination),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
        )
        OtpField(
            value = code,
            length = challenge.codeLength,
            isError = error != null,
            enabled = !locked,
            focusRequester = codeFocus,
            onValue = { v ->
                if (busy) return@OtpField
                code = v
                if (error != null && v.text.isNotEmpty() && !locked) error = null
                if (v.text.length == challenge.codeLength) verify(v.text)
            },
            modifier = Modifier.graphicsLayer { translationX = shake.value },
        )
        Box(Modifier.height(56.dp).padding(top = 12.dp), contentAlignment = Alignment.TopCenter) {
            when {
                busy -> LoadingIndicator(Modifier.size(36.dp))
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
        if (retryAvailable && !busy) {
            Row(horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = ::retry) { Text(stringResource(R.string.action_try_again)) }
                TextButton(onClick = onImport) { Text(stringResource(R.string.auth_import_session)) }
            }
        }
        val wait = ((challenge.resendAvailableAtMs - now) / 1000).coerceAtLeast(0)
        TextButton(onClick = ::resend, enabled = wait == 0L && !busy) {
            Text(if (wait > 0) stringResource(R.string.auth_resend_in, wait) else stringResource(R.string.auth_resend))
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.auth_different_email)) }

    }
}

/**
 * Six separate-looking cells over one real text field, so paste, SMS/email code autofill and
 * backspace all work like a normal input.
 */
@Composable
private fun OtpField(
    value: TextFieldValue, length: Int, isError: Boolean, enabled: Boolean,
    focusRequester: FocusRequester, onValue: (TextFieldValue) -> Unit, modifier: Modifier = Modifier,
) {
    val focus = focusRequester
    LaunchedEffect(Unit) { focus.requestFocus() }
    val cs = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = { v ->
            val digits = v.text.filter(Char::isDigit).take(length)
            onValue(TextFieldValue(digits, TextRange(digits.length)))
        },
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
        modifier = modifier.focusRequester(focus).semantics { contentType = ContentType.SmsOtpCode },
        decorationBox = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(length) { i ->
                    val ch = value.text.getOrNull(i)
                    val current = i == value.text.length && enabled
                    val scale = remember { Animatable(1f) }
                    LaunchedEffect(ch) { if (ch != null) { scale.snapTo(1.12f); scale.animateTo(1f, spring(0.4f, Spring.StiffnessMedium)) } }
                    Box(
                        Modifier
                            .size(width = 48.dp, height = 60.dp)
                            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (ch != null) cs.secondaryContainer else cs.surfaceContainerHigh)
                            .border(
                                width = if (current || isError) 2.dp else 0.dp,
                                color = when { isError -> cs.error; current -> cs.primary; else -> cs.surfaceContainerHigh },
                                shape = RoundedCornerShape(16.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ch?.toString() ?: "",
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (ch != null) cs.onSecondaryContainer else cs.onSurface,
                        )
                    }
                }
            }
        },
    )
}
