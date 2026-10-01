package com.lagradost.quicknovel.auth

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Email + password login / sign up backed by [SupabaseAuth]. */
class LoginActivity : ComponentActivity() {
    private var callbackHandler: ((Uri) -> Unit)? = null

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.data?.let { callbackHandler?.invoke(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Going back from the login screen exits the app instead of falling through to MainActivity.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finishAffinity()
            }
        })

        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    LoginScreen(
                        onAuthenticated = { finish() },
                        onSkip = {
                            SupabaseAuth.skipForSession()
                            finish()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(onAuthenticated: () -> Unit, onSkip: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isSignUp by rememberSaveable { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var infoMessage by rememberSaveable { mutableStateOf<String?>(null) }

    callbackHandler = { uri ->
        isLoading = true
        scope.launch {
            when (val result = SupabaseAuth.processOAuthCallback(context, uri)) {
                is AuthResult.Success -> onAuthenticated()
                is AuthResult.Failure -> { isLoading = false; errorMessage = result.message }
            }
        }
    }
    // Android may create this activity directly from the deep link while the app was closed.
    if (intent?.data?.scheme == "fastnovel") {
        val callback = intent.data
        intent.data = null
        callback?.let { callbackHandler?.invoke(it) }
    }

    fun submit() {
        val trimmedEmail = email.trim()
        errorMessage = null
        infoMessage = null

        if (trimmedEmail.isBlank() || !trimmedEmail.contains("@")) {
            errorMessage = "Enter a valid email address"
            return
        }
        if (password.length < 6) {
            errorMessage = "Password must be at least 6 characters"
            return
        }

        isLoading = true
        scope.launch {
            val result = if (isSignUp) {
                SupabaseAuth.signUp(context, trimmedEmail, password)
            } else {
                SupabaseAuth.signIn(context, trimmedEmail, password)
            }
            isLoading = false

            when (result) {
                is AuthResult.Success -> {
                    if (result.needsEmailConfirmation) {
                        infoMessage = "Check your email to confirm your account, then sign in"
                        isSignUp = false
                        password = ""
                    } else {
                        onAuthenticated()
                    }
                }

                is AuthResult.Failure -> errorMessage = result.message
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "FastNovel",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isSignUp) "Create an account" else "Sign in to sync your library",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                errorMessage = null
                val url = SupabaseAuth.discordAuthorizeUrl()
                if (url == null) errorMessage = "Login is not configured in this build"
                else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Continue with Discord") }

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = !isLoading,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !isLoading,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        val error = errorMessage
        if (error != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        val info = infoMessage
        if (info != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = info,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            Button(
                onClick = { submit() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (isSignUp) "Create account" else "Sign in")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = {
                isSignUp = !isSignUp
                errorMessage = null
                infoMessage = null
            },
            enabled = !isLoading
        ) {
            Text(
                text = if (isSignUp) {
                    "Already have an account? Sign in"
                } else {
                    "No account? Sign up"
                }
            )
        }

        TextButton(
            onClick = onSkip,
            enabled = !isLoading
        ) {
            Text(text = "Continue without account")
        }
    }
}
