package com.email.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.email.app.R
import com.email.api.AuthConfig
import com.email.app.net.ServerApi
import kotlinx.coroutines.launch

/** Sign-in: the server's address, then single sign-on in a browser tab, or a password. */
@Composable
fun SignInScreen(finishing: Boolean, error: String?) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf(c.settings.value.server.removePrefix("https://")) }
    var config by remember { mutableStateOf<Pair<String, AuthConfig>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf(error) }
    var username by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Backdrop {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        // The launcher icon is adaptive (painterResource can't draw those): its parts, drawn here.
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(Color(0xFF5B3FD1), Color(0xFF0F9D77))))) {
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
        Text(config?.second?.serverName ?: "SuperAwesomeMagicalEmail", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            if (config == null) "Sign in to your mail server to read and send your mail here." else "Sign in to your mail",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
        if (finishing) {
            CircularProgressIndicator()
            Text("Finishing signing in…")
            return@Column
        }
        val current = config
        if (current == null) {
            OutlinedTextField(
                address, { address = it },
                label = { Text("Server address") },
                placeholder = { Text("mail.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && address.isNotBlank(),
                onClick = {
                    val server = ServerApi.normalize(address)
                    if (server == null) {
                        message = "That isn't a web address"
                        return@Button
                    }
                    busy = true
                    message = null
                    scope.launch {
                        try {
                            config = server to c.api.authConfig(server)
                        } catch (e: Exception) {
                            message = "Couldn't reach a SuperAwesomeMagicalEmail server there (${e.message ?: e.javaClass.simpleName})"
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (busy) "Checking…" else "Continue") }
        } else {
            val (server, auth) = current
            auth.oidc?.let { oidc ->
                Button(onClick = { c.signIn.startBrowserSignIn(server) }, modifier = Modifier.fillMaxWidth()) { Text(oidc.label) }
            }
            if (auth.localLogin || showPassword || auth.oidc == null) {
                OutlinedTextField(username, { username = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    password, { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                c.signIn.signInWithPassword(server, username.trim(), password)
                            } catch (e: Exception) {
                                message = e.message ?: "Couldn't sign in"
                            }
                            busy = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Signing in…" else "Sign in with password") }
            } else {
                TextButton(onClick = { showPassword = true }) { Text("Sign in with a password (admins)") }
            }
            TextButton(onClick = { config = null }) { Text("Use another server") }
            Text("Server ${auth.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}
