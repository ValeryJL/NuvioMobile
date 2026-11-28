package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.TotpEnrollmentData
import com.nuvio.app.core.auth.formatTotpSecretForDisplay
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_security_change_email_title
import nuvio.composeapp.generated.resources.settings_security_change_password_title
import nuvio.composeapp.generated.resources.settings_security_confirm_new_password
import nuvio.composeapp.generated.resources.settings_security_current_password
import nuvio.composeapp.generated.resources.settings_security_email_change_note
import nuvio.composeapp.generated.resources.settings_security_email_change_sent
import nuvio.composeapp.generated.resources.settings_security_new_email
import nuvio.composeapp.generated.resources.settings_security_new_password
import nuvio.composeapp.generated.resources.settings_security_password_mismatch
import nuvio.composeapp.generated.resources.settings_security_password_too_short
import nuvio.composeapp.generated.resources.settings_security_password_updated
import nuvio.composeapp.generated.resources.settings_security_save_action
import nuvio.composeapp.generated.resources.settings_security_totp_activate
import nuvio.composeapp.generated.resources.settings_security_totp_code_label
import nuvio.composeapp.generated.resources.settings_security_totp_copy_key
import nuvio.composeapp.generated.resources.settings_security_totp_enabled_toast
import nuvio.composeapp.generated.resources.settings_security_totp_key_copied
import nuvio.composeapp.generated.resources.settings_security_totp_manual_key_label
import nuvio.composeapp.generated.resources.settings_security_totp_setup_subtitle
import nuvio.composeapp.generated.resources.settings_security_totp_setup_title
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TotpSetupDialog(
    onDismiss: () -> Unit,
    onEnrolled: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current

    var isLoadingEnrollment by remember { mutableStateOf(true) }
    var enrollment by remember { mutableStateOf<TotpEnrollmentData?>(null) }
    var enrollError by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var isVerifying by remember { mutableStateOf(false) }
    var verifyError by remember { mutableStateOf<String?>(null) }

    val keyCopiedText = stringResource(Res.string.settings_security_totp_key_copied)
    val enabledToastText = stringResource(Res.string.settings_security_totp_enabled_toast)

    LaunchedEffect(Unit) {
        isLoadingEnrollment = true
        enrollError = null
        AuthRepository.enrollTotp()
            .onSuccess { enrollment = it }
            .onFailure { enrollError = it.message }
        isLoadingEnrollment = false
    }

    SecurityDialogContainer(onDismiss = { if (!isVerifying) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Text(
                text = stringResource(Res.string.settings_security_totp_setup_title),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = stringResource(Res.string.settings_security_totp_setup_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )

            when {
                isLoadingEnrollment -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = NuvioTokens.Space.s24),
                        contentAlignment = Alignment.Center,
                    ) {
                        NuvioLoadingIndicator(color = tokens.colors.accent)
                    }
                }

                enrollError != null -> {
                    Text(
                        text = enrollError.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.colors.danger,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(text = stringResource(Res.string.action_cancel))
                        }
                    }
                }

                enrollment != null -> {
                    val data = enrollment!!
                    if (data.qrCodeSvg.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .size(196.dp)
                                .clip(RoundedCornerShape(NuvioTokens.Radius.md))
                                .background(Color.White)
                                .padding(NuvioTokens.Space.s12),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = data.qrCodeSvg.encodeToByteArray(),
                                contentDescription = stringResource(Res.string.settings_security_totp_setup_title),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(tokens.shapes.compactCard)
                            .background(tokens.colors.surfaceCard)
                            .padding(NuvioTokens.Space.s12),
                        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s6),
                    ) {
                        Text(
                            text = stringResource(Res.string.settings_security_totp_manual_key_label),
                            style = MaterialTheme.typography.labelMedium,
                            color = tokens.colors.textMuted,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
                        ) {
                            Text(
                                text = formatTotpSecretForDisplay(data.secret),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = tokens.colors.textPrimary,
                            )
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(data.secret))
                                    NuvioToastController.show(keyCopiedText)
                                },
                                shape = tokens.shapes.chip,
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.size(NuvioTokens.Space.s6))
                                Text(text = stringResource(Res.string.settings_security_totp_copy_key))
                            }
                        }
                    }

                    SecurityOutlinedField(
                        value = code,
                        onValueChange = { next ->
                            val filtered = next.filter(Char::isDigit).take(6)
                            code = filtered
                            verifyError = null
                        },
                        label = stringResource(Res.string.settings_security_totp_code_label),
                        keyboardType = KeyboardType.Number,
                    )

                    verifyError?.let { errorText ->
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.colors.danger,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !isVerifying,
                        ) {
                            Text(text = stringResource(Res.string.action_cancel))
                        }
                        Button(
                            onClick = {
                                if (isVerifying || code.length != 6) return@Button
                                isVerifying = true
                                verifyError = null
                                scope.launch {
                                    AuthRepository.verifyTotp(
                                        factorId = data.factorId,
                                        code = code,
                                    ).onSuccess {
                                        NuvioToastController.show(enabledToastText)
                                        onEnrolled()
                                    }.onFailure { error ->
                                        verifyError = error.message
                                    }
                                    isVerifying = false
                                }
                            },
                            enabled = !isVerifying && code.length == 6,
                            shape = tokens.shapes.button,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = tokens.colors.accent,
                                contentColor = tokens.colors.onAccent,
                            ),
                        ) {
                            if (isVerifying) {
                                NuvioLoadingIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = tokens.colors.onAccent,
                                )
                            } else {
                                Text(text = stringResource(Res.string.settings_security_totp_activate))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChangePasswordDialog(
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()

    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val tooShortText = stringResource(Res.string.settings_security_password_too_short)
    val mismatchText = stringResource(Res.string.settings_security_password_mismatch)
    val updatedText = stringResource(Res.string.settings_security_password_updated)

    SecurityDialogContainer(onDismiss = { if (!isBusy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Text(
                text = stringResource(Res.string.settings_security_change_password_title),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )

            SecurityPasswordField(
                value = currentPassword,
                onValueChange = { currentPassword = it; errorMessage = null },
                label = stringResource(Res.string.settings_security_current_password),
            )
            SecurityPasswordField(
                value = newPassword,
                onValueChange = { newPassword = it; errorMessage = null },
                label = stringResource(Res.string.settings_security_new_password),
            )
            SecurityPasswordField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; errorMessage = null },
                label = stringResource(Res.string.settings_security_confirm_new_password),
            )

            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isBusy,
                ) {
                    Text(text = stringResource(Res.string.action_cancel))
                }
                Button(
                    onClick = {
                        if (isBusy) return@Button
                        if (newPassword.length < 6) {
                            errorMessage = tooShortText
                            return@Button
                        }
                        if (newPassword != confirmPassword) {
                            errorMessage = mismatchText
                            return@Button
                        }
                        isBusy = true
                        errorMessage = null
                        scope.launch {
                            AuthRepository.updatePasswordWithCurrent(
                                currentPassword = currentPassword,
                                newPassword = newPassword,
                            ).onSuccess {
                                NuvioToastController.show(updatedText)
                                onDismiss()
                            }.onFailure { error ->
                                errorMessage = error.message
                            }
                            isBusy = false
                        }
                    },
                    enabled = !isBusy && currentPassword.isNotBlank() && newPassword.isNotBlank(),
                    shape = tokens.shapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.colors.accent,
                        contentColor = tokens.colors.onAccent,
                    ),
                ) {
                    if (isBusy) {
                        NuvioLoadingIndicator(
                            modifier = Modifier.size(16.dp),
                            color = tokens.colors.onAccent,
                        )
                    } else {
                        Text(text = stringResource(Res.string.settings_security_save_action))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ChangeEmailDialog(
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val scope = rememberCoroutineScope()

    var currentPassword by remember { mutableStateOf("") }
    var newEmail by remember { mutableStateOf("") }
    var isBusy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val sentText = stringResource(Res.string.settings_security_email_change_sent)

    SecurityDialogContainer(onDismiss = { if (!isBusy) onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s12),
        ) {
            Text(
                text = stringResource(Res.string.settings_security_change_email_title),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = stringResource(Res.string.settings_security_email_change_note),
                style = MaterialTheme.typography.bodyMedium,
                color = tokens.colors.textMuted,
            )

            SecurityPasswordField(
                value = currentPassword,
                onValueChange = { currentPassword = it; errorMessage = null },
                label = stringResource(Res.string.settings_security_current_password),
            )
            SecurityOutlinedField(
                value = newEmail,
                onValueChange = { newEmail = it; errorMessage = null },
                label = stringResource(Res.string.settings_security_new_email),
                keyboardType = KeyboardType.Email,
            )

            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isBusy,
                ) {
                    Text(text = stringResource(Res.string.action_cancel))
                }
                Button(
                    onClick = {
                        if (isBusy) return@Button
                        isBusy = true
                        errorMessage = null
                        scope.launch {
                            AuthRepository.requestEmailChange(
                                currentPassword = currentPassword,
                                newEmail = newEmail,
                            ).onSuccess {
                                NuvioToastController.show(sentText)
                                onDismiss()
                            }.onFailure { error ->
                                errorMessage = error.message
                            }
                            isBusy = false
                        }
                    },
                    enabled = !isBusy && currentPassword.isNotBlank() && newEmail.isNotBlank(),
                    shape = tokens.shapes.button,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = tokens.colors.accent,
                        contentColor = tokens.colors.onAccent,
                    ),
                ) {
                    if (isBusy) {
                        NuvioLoadingIndicator(
                            modifier = Modifier.size(16.dp),
                            color = tokens.colors.onAccent,
                        )
                    } else {
                        Text(text = stringResource(Res.string.settings_security_save_action))
                    }
                }
            }
        }
    }
}

@Composable
private fun SecurityDialogContainer(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp),
            shape = tokens.shapes.card,
            color = tokens.colors.surfacePopover,
            tonalElevation = tokens.elevation.overlay,
            shadowElevation = tokens.elevation.overlay,
        ) {
            Box(modifier = Modifier.padding(NuvioTokens.Space.s20)) {
                content()
            }
        }
    }
}

@Composable
private fun SecurityOutlinedField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val tokens = MaterialTheme.nuvio
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(text = label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = tokens.shapes.compactCard,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.colors.borderFocus,
            unfocusedBorderColor = tokens.colors.borderDefault,
            focusedContainerColor = tokens.colors.surfaceCard,
            unfocusedContainerColor = tokens.colors.surfaceCard,
            cursorColor = tokens.colors.accent,
        ),
    )
}

@Composable
private fun SecurityPasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    val tokens = MaterialTheme.nuvio
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(text = label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = null,
                    tint = tokens.colors.textMuted,
                )
            }
        },
        shape = tokens.shapes.compactCard,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = tokens.colors.borderFocus,
            unfocusedBorderColor = tokens.colors.borderDefault,
            focusedContainerColor = tokens.colors.surfaceCard,
            unfocusedContainerColor = tokens.colors.surfaceCard,
            cursorColor = tokens.colors.accent,
        ),
    )
}
