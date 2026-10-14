package com.guuslab.nemi.android.compose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.guuslab.nemi.android.NemiAuth
import com.guuslab.nemi.android.NemiSignInRequest
import com.guuslab.nemi.android.NemiSignInResult
import kotlinx.coroutines.launch

/** Colors of [SignInWithNemiButton]. */
public enum class NemiButtonStyle {
    /** Black pill with white text. For light backgrounds. The default. */
    Black,

    /** White pill with black text and a hairline border. For dark backgrounds. */
    White,

    /** [Black] in light mode, [White] in dark mode. */
    Auto,
}

/**
 * The "Sign in with Nemi" button: a pill, 48dp tall, announced to TalkBack as a button
 * labelled [text].
 */
@Composable
public fun SignInWithNemiButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NemiButtonStyle = NemiButtonStyle.Black,
    enabled: Boolean = true,
    text: String = "Sign in with Nemi",
) {
    val dark = when (style) {
        NemiButtonStyle.Black -> true
        NemiButtonStyle.White -> false
        NemiButtonStyle.Auto -> !isSystemInDarkTheme()
    }
    val background = if (dark) Color.Black else Color.White
    val foreground = if (dark) Color.White else Color.Black
    val shape = RoundedCornerShape(percent = 50)

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .defaultMinSize(minWidth = 200.dp, minHeight = 48.dp)
            .clip(shape)
            .background(background, shape)
            .then(if (dark) Modifier else Modifier.border(1.dp, Color(0x33000000), shape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = text
                role = Role.Button
                if (!enabled) disabled()
                onClick(label = text) {
                    onClick()
                    true
                }
            }
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        BasicText(
            text = text,
            style = TextStyle(color = foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        )
    }
}

/**
 * Wires the [com.guuslab.nemi.android.SignInWithNemi] contract into Compose and finishes the
 * sign in. Returns a function that starts it.
 *
 * ```
 * val signIn = rememberSignInWithNemi(nemiAuth) { result -> ... }
 * SignInWithNemiButton(onClick = { signIn(null) })
 * ```
 */
@Composable
public fun rememberSignInWithNemi(
    auth: NemiAuth,
    onResult: (NemiSignInResult) -> Unit,
): (NemiSignInRequest?) -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnResult = rememberUpdatedState(onResult)
    val contract = remember(auth) { auth.signInContract() }
    val launcher = rememberLauncherForActivityResult(contract) { result ->
        scope.launch { currentOnResult.value(auth.completeSignIn(result)) }
    }
    return remember(launcher) { { request: NemiSignInRequest? -> launcher.launch(request) } }
}

@Preview(name = "Black")
@Composable
private fun PreviewBlack() {
    SignInWithNemiButton(onClick = {})
}

@Preview(name = "White", backgroundColor = 0xFF111111, showBackground = true)
@Composable
private fun PreviewWhite() {
    SignInWithNemiButton(onClick = {}, style = NemiButtonStyle.White)
}
