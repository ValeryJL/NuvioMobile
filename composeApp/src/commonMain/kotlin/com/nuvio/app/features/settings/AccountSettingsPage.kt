package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.auth.TotpFactorInfo
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.compose_settings_page_account
import nuvio.composeapp.generated.resources.auth_account_deletion_failed
import nuvio.composeapp.generated.resources.settings_account_delete_account
import nuvio.composeapp.generated.resources.settings_account_delete_account_description
import nuvio.composeapp.generated.resources.settings_account_delete_confirm_message
import nuvio.composeapp.generated.resources.settings_account_delete_confirm_title
import nuvio.composeapp.generated.resources.settings_account_email
import nuvio.composeapp.generated.resources.settings_account_not_signed_in
import nuvio.composeapp.generated.resources.settings_account_sign_out
import nuvio.composeapp.generated.resources.settings_account_sign_out_confirm_message
import nuvio.composeapp.generated.resources.settings_account_sign_out_confirm_title
import nuvio.composeapp.generated.resources.settings_account_status
import nuvio.composeapp.generated.resources.settings_account_status_anonymous
import nuvio.composeapp.generated.resources.settings_account_status_signed_in
import nuvio.composeapp.generated.resources.settings_security_change_email_subtitle
import nuvio.composeapp.generated.resources.settings_security_change_email_title
import nuvio.composeapp.generated.resources.settings_security_change_password_subtitle
import nuvio.composeapp.generated.resources.settings_security_change_password_title
import nuvio.composeapp.generated.resources.settings_security_title
import nuvio.composeapp.generated.resources.settings_security_totp_disable_action
import nuvio.composeapp.generated.resources.settings_security_totp_disable_message
import nuvio.composeapp.generated.resources.settings_security_totp_disable_title
import nuvio.composeapp.generated.resources.settings_security_totp_disabled
import nuvio.composeapp.generated.resources.settings_security_totp_disabled_toast
import nuvio.composeapp.generated.resources.settings_security_totp_enabled
import nuvio.composeapp.generated.resources.settings_security_totp_setup_action
import nuvio.composeapp.generated.resources.settings_security_totp_title
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.accountSettingsContent(
    isTablet: Boolean,
) {
    item {
        AccountSettingsBody(isTablet = isTablet)
    }
}

@Composable
private fun AccountSettingsBody(
    isTablet: Boolean,
) {
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showSignOutConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var isDeletingAccount by remember { mutableStateOf(false) }
    var deleteErrorMessage by remember { mutableStateOf<String?>(null) }
    var verifiedTotpFactors by remember { mutableStateOf<List<TotpFactorInfo>>(emptyList()) }
    var showTotpSetupDialog by remember { mutableStateOf(false) }
    var showTotpDisableConfirm by remember { mutableStateOf(false) }
    var isDisablingTotp by remember { mutableStateOf(false) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showChangeEmailDialog by remember { mutableStateOf(false) }
    val deleteAccountFallbackMessage = stringResource(Res.string.auth_account_deletion_failed)
    val totpDisabledToastText = stringResource(Res.string.settings_security_totp_disabled_toast)
    val signedInAccount = (authState as? AuthState.Authenticated)?.takeUnless { it.isAnonymous }
    val canDeleteAccount = AppFeaturePolicy.accountDeletionEnabled && authState is AuthState.Authenticated

    LaunchedEffect(signedInAccount?.userId) {
        if (signedInAccount == null) {
            verifiedTotpFactors = emptyList()
        } else {
            AuthRepository.listVerifiedTotpFactors()
                .onSuccess { verifiedTotpFactors = it }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NuvioSurfaceCard {
            Text(
                text = stringResource(Res.string.compose_settings_page_account),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(14.dp))

            when (val state = authState) {
                is AuthState.Authenticated -> {
                    AccountInfoRow(
                        label = stringResource(Res.string.settings_account_status),
                        value = if (state.isAnonymous) {
                            stringResource(Res.string.settings_account_status_anonymous)
                        } else {
                            stringResource(Res.string.settings_account_status_signed_in)
                        },
                        valueColor = MaterialTheme.colorScheme.primary,
                    )
                    state.email?.takeUnless { state.isAnonymous }?.let { email ->
                        Spacer(modifier = Modifier.height(8.dp))
                        AccountInfoRow(
                            label = stringResource(Res.string.settings_account_email),
                            value = email,
                        )
                    }
                }
                else -> {
                    Text(
                        text = stringResource(Res.string.settings_account_not_signed_in),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (signedInAccount != null) {
            AccountSecurityCard(
                totpEnabled = verifiedTotpFactors.isNotEmpty(),
                onTotpActionClick = {
                    if (verifiedTotpFactors.isNotEmpty()) {
                        showTotpDisableConfirm = true
                    } else {
                        showTotpSetupDialog = true
                    }
                },
                onChangePasswordClick = { showChangePasswordDialog = true },
                onChangeEmailClick = { showChangeEmailDialog = true },
            )
        }

        NuvioPrimaryButton(
            text = stringResource(Res.string.settings_account_sign_out),
            onClick = { showSignOutConfirm = true },
        )

        if (canDeleteAccount) {
            DeleteAccountCard(
                errorMessage = deleteErrorMessage,
                onDeleteClick = {
                    deleteErrorMessage = null
                    showDeleteConfirm = true
                },
            )
        }
    }

    NuvioStatusModal(
        title = stringResource(Res.string.settings_account_sign_out_confirm_title),
        message = stringResource(Res.string.settings_account_sign_out_confirm_message),
        isVisible = showSignOutConfirm,
        confirmText = stringResource(Res.string.settings_account_sign_out),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            showSignOutConfirm = false
            scope.launch { AuthRepository.signOut() }
        },
        onDismiss = { showSignOutConfirm = false },
    )

    NuvioStatusModal(
        title = stringResource(Res.string.settings_account_delete_confirm_title),
        message = stringResource(Res.string.settings_account_delete_confirm_message),
        isVisible = showDeleteConfirm,
        isBusy = isDeletingAccount,
        destructive = true,
        confirmText = stringResource(Res.string.settings_account_delete_account),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            if (isDeletingAccount) return@NuvioStatusModal
            isDeletingAccount = true
            scope.launch {
                val result = AuthRepository.deleteAccount()
                isDeletingAccount = false
                showDeleteConfirm = false
                deleteErrorMessage = if (result.isSuccess) {
                    null
                } else {
                    AuthRepository.error.value
                        ?: result.exceptionOrNull()?.message
                        ?: deleteAccountFallbackMessage
                }
            }
        },
        onDismiss = {
            if (!isDeletingAccount) {
                showDeleteConfirm = false
            }
        },
    )

    NuvioStatusModal(
        title = stringResource(Res.string.settings_security_totp_disable_title),
        message = stringResource(Res.string.settings_security_totp_disable_message),
        isVisible = showTotpDisableConfirm,
        isBusy = isDisablingTotp,
        destructive = true,
        confirmText = stringResource(Res.string.settings_security_totp_disable_action),
        dismissText = stringResource(Res.string.action_cancel),
        onConfirm = {
            val factorId = verifiedTotpFactors.firstOrNull()?.id ?: run {
                showTotpDisableConfirm = false
                return@NuvioStatusModal
            }
            if (isDisablingTotp) return@NuvioStatusModal
            isDisablingTotp = true
            scope.launch {
                AuthRepository.unenrollTotp(factorId)
                    .onSuccess {
                        NuvioToastController.show(totpDisabledToastText)
                        AuthRepository.listVerifiedTotpFactors()
                            .onSuccess { verifiedTotpFactors = it }
                    }
                    .onFailure { error ->
                        error.message?.let(NuvioToastController::show)
                    }
                isDisablingTotp = false
                showTotpDisableConfirm = false
            }
        },
        onDismiss = {
            if (!isDisablingTotp) {
                showTotpDisableConfirm = false
            }
        },
    )

    if (showTotpSetupDialog) {
        TotpSetupDialog(
            onDismiss = { showTotpSetupDialog = false },
            onEnrolled = {
                showTotpSetupDialog = false
                scope.launch {
                    AuthRepository.listVerifiedTotpFactors()
                        .onSuccess { verifiedTotpFactors = it }
                }
            },
        )
    }

    if (showChangePasswordDialog) {
        ChangePasswordDialog(onDismiss = { showChangePasswordDialog = false })
    }

    if (showChangeEmailDialog) {
        ChangeEmailDialog(onDismiss = { showChangeEmailDialog = false })
    }
}

@Composable
private fun AccountSecurityCard(
    totpEnabled: Boolean,
    onTotpActionClick: () -> Unit,
    onChangePasswordClick: () -> Unit,
    onChangeEmailClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.settings_security_title),
            style = MaterialTheme.typography.titleLarge,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.settings_security_totp_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        if (totpEnabled) {
                            Res.string.settings_security_totp_enabled
                        } else {
                            Res.string.settings_security_totp_disabled
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (totpEnabled) tokens.colors.accent else tokens.colors.textMuted,
                    fontWeight = if (totpEnabled) FontWeight.Medium else FontWeight.Normal,
                )
            }
            OutlinedButton(
                onClick = onTotpActionClick,
                shape = tokens.shapes.button,
            ) {
                Text(
                    text = stringResource(
                        if (totpEnabled) {
                            Res.string.settings_security_totp_disable_action
                        } else {
                            Res.string.settings_security_totp_setup_action
                        },
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = tokens.colors.borderDefault)
        Spacer(modifier = Modifier.height(4.dp))

        SecurityActionRow(
            title = stringResource(Res.string.settings_security_change_password_title),
            subtitle = stringResource(Res.string.settings_security_change_password_subtitle),
            onClick = onChangePasswordClick,
        )

        HorizontalDivider(color = tokens.colors.borderDefault)

        SecurityActionRow(
            title = stringResource(Res.string.settings_security_change_email_title),
            subtitle = stringResource(Res.string.settings_security_change_email_subtitle),
            onClick = onChangeEmailClick,
        )
    }
}

@Composable
private fun SecurityActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(tokens.shapes.compactCard)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.textPrimary,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.textMuted,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = tokens.colors.textMuted,
        )
    }
}

@Composable
private fun DeleteAccountCard(
    errorMessage: String?,
    onDeleteClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio

    NuvioSurfaceCard {
        Text(
            text = stringResource(Res.string.settings_account_delete_account),
            style = MaterialTheme.typography.titleMedium,
            color = tokens.colors.textPrimary,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.settings_account_delete_account_description),
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textMuted,
        )
        errorMessage?.let { message ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.colors.danger,
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onDeleteClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(NuvioTokens.Space.s48 + NuvioTokens.Space.s4),
            shape = tokens.shapes.button,
            colors = ButtonDefaults.buttonColors(
                containerColor = tokens.colors.danger,
                contentColor = tokens.colors.textInverse,
            ),
        ) {
            Text(
                text = stringResource(Res.string.settings_account_delete_account),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AccountInfoRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = valueColor,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
