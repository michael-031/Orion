package com.orion.proxy.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Shuffle
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orion.proxy.CustomHotspotConfig
import com.orion.proxy.R
import com.orion.proxy.WifiDirectCredentialValidator
import com.orion.proxy.ui.theme.AmberRecommended
import com.orion.proxy.ui.theme.CoralError
import com.orion.proxy.ui.theme.EmeraldActive
import com.orion.proxy.ui.theme.JetBrainsMonoFontFamily
import com.orion.proxy.ui.theme.ObsidianBackground
import com.orion.proxy.ui.theme.ObsidianBorder
import com.orion.proxy.ui.theme.ObsidianSurfaceElevated
import com.orion.proxy.ui.theme.PureWhite
import com.orion.proxy.ui.theme.SlateDim
import com.orion.proxy.ui.theme.SlateMuted

@Composable
fun CredentialsConfigSheet(
    initialConfig: CustomHotspotConfig,
    currentLiveSsid: String?,
    currentLivePassphrase: String?,
    isProxyRunning: Boolean,
    supportsCustomCredentials: Boolean,
    onSave: (ssidTag: String, ssidName: String, passphrase: String) -> Unit,
    onUseAuto: () -> Unit
) {
    // Pre-populate from saved custom config, or extract tag/name from live SSID if available
    val initialTagAndName = remember(initialConfig, currentLiveSsid) {
        if (initialConfig.enabled) {
            initialConfig.ssidTag to initialConfig.ssidName
        } else if (!currentLiveSsid.isNullOrBlank() && currentLiveSsid.startsWith("DIRECT-") && currentLiveSsid.length >= 9) {
            val tag = currentLiveSsid.substring(7, 9)
            val rest = currentLiveSsid.substring(9).removePrefix("-")
            tag to rest
        } else {
            initialConfig.ssidTag to initialConfig.ssidName
        }
    }

    var ssidTag by remember(initialTagAndName) { mutableStateOf(initialTagAndName.first) }
    var ssidName by remember(initialTagAndName) { mutableStateOf(initialTagAndName.second) }
    var passphrase by remember(initialConfig, currentLivePassphrase) {
        val defaultPass = when {
            initialConfig.passphrase.isNotBlank() -> initialConfig.passphrase
            !currentLivePassphrase.isNullOrBlank() -> currentLivePassphrase
            else -> WifiDirectCredentialValidator.generatePassphrase(12)
        }
        mutableStateOf(defaultPass)
    }
    var passwordVisible by remember { mutableStateOf(true) }

    val validation = remember(ssidTag, ssidName, passphrase) {
        WifiDirectCredentialValidator.validateAll(ssidTag, ssidName, passphrase)
    }
    val previewSsid = remember(ssidTag, ssidName) {
        WifiDirectCredentialValidator.buildFullSsid(ssidTag, ssidName)
    }
    val previewBytes = remember(ssidTag, ssidName) {
        WifiDirectCredentialValidator.ssidByteLength(ssidTag, ssidName)
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = PureWhite,
        unfocusedTextColor = PureWhite,
        focusedBorderColor = PureWhite,
        unfocusedBorderColor = ObsidianBorder,
        errorBorderColor = CoralError,
        focusedLabelColor = PureWhite,
        unfocusedLabelColor = SlateMuted,
        cursorColor = PureWhite,
        focusedContainerColor = ObsidianBackground,
        unfocusedContainerColor = ObsidianBackground
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.edit_creds_title),
                style = MaterialTheme.typography.headlineSmall,
                color = PureWhite
            )
            Surface(
                shape = CircleShape,
                color = ObsidianSurfaceElevated,
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Text(
                    text = if (initialConfig.enabled) {
                        stringResource(R.string.badge_custom_config)
                    } else {
                        stringResource(R.string.badge_auto_config)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (initialConfig.enabled) EmeraldActive else SlateMuted,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Clear Android DIRECT- standard explanation card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = ObsidianSurfaceElevated,
            border = BorderStroke(1.dp, ObsidianBorder)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (supportsCustomCredentials) AmberRecommended else CoralError,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(18.dp)
                )
                Text(
                    text = if (supportsCustomCredentials) {
                        stringResource(R.string.edit_creds_prefix_note)
                    } else {
                        stringResource(R.string.edit_creds_legacy_note)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = SlateMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Live Broadcast SSID Preview
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = ObsidianBackground,
            border = BorderStroke(
                1.dp,
                if (validation.tagError == null && validation.nameError == null) ObsidianBorder else CoralError
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.edit_creds_preview_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = SlateMuted
                    )
                    Text(
                        text = "$previewBytes/${WifiDirectCredentialValidator.MAX_SSID_BYTES} B",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (previewBytes <= WifiDirectCredentialValidator.MAX_SSID_BYTES) SlateDim else CoralError
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = previewSsid,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = PureWhite,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("ssid_preview_text")
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // DIRECT- [2-char tag] - [Network Name] input row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                color = ObsidianSurfaceElevated,
                border = BorderStroke(1.dp, ObsidianBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = WifiDirectCredentialValidator.MANDATORY_PREFIX,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = SlateMuted
                    )
                }
            }

            OutlinedTextField(
                value = ssidTag,
                onValueChange = { raw ->
                    val filtered = raw.filter { it.isLetterOrDigit() && it.code < 128 }.take(2)
                    ssidTag = filtered
                },
                enabled = supportsCustomCredentials,
                label = { Text(stringResource(R.string.edit_creds_tag_label), maxLines = 1) },
                placeholder = { Text("OR", fontFamily = JetBrainsMonoFontFamily) },
                singleLine = true,
                isError = validation.tagError != null,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .width(96.dp)
                    .testTag("ssid_tag_input")
            )

            OutlinedTextField(
                value = ssidName,
                onValueChange = { raw ->
                    ssidName = raw.replace("\n", "").replace("\r", "")
                },
                enabled = supportsCustomCredentials,
                label = { Text(stringResource(R.string.edit_creds_name_label), maxLines = 1) },
                placeholder = { Text("Orion", fontFamily = JetBrainsMonoFontFamily) },
                singleLine = true,
                isError = validation.nameError != null,
                textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("ssid_name_input")
            )
        }

        val ssidErrorText = validation.tagError ?: validation.nameError
        if (ssidErrorText != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = ssidErrorText,
                style = MaterialTheme.typography.bodyMedium,
                color = CoralError
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Passphrase input with visibility toggle + random generator button
        OutlinedTextField(
            value = passphrase,
            onValueChange = { raw ->
                passphrase = raw.take(WifiDirectCredentialValidator.MAX_PASSPHRASE_LENGTH)
            },
            enabled = supportsCustomCredentials,
            label = { Text(stringResource(R.string.edit_creds_passphrase_label)) },
            singleLine = true,
            isError = validation.passphraseError != null,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            textStyle = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMonoFontFamily),
            colors = fieldColors,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done
            ),
            supportingText = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = validation.passphraseError ?: "WPA2-PSK compatible with Windows",
                        color = if (validation.passphraseError != null) CoralError else SlateDim
                    )
                    Text(
                        text = "${passphrase.length}/${WifiDirectCredentialValidator.MAX_PASSPHRASE_LENGTH}",
                        color = SlateDim,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = supportsCustomCredentials
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = stringResource(R.string.cd_toggle_password),
                            tint = SlateMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { passphrase = WifiDirectCredentialValidator.generatePassphrase(12) },
                        enabled = supportsCustomCredentials,
                        modifier = Modifier.testTag("generate_passphrase_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = stringResource(R.string.cd_generate_password),
                            tint = PureWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("passphrase_input")
        )

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onUseAuto,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("credentials_use_auto_button"),
                shape = CircleShape,
                border = BorderStroke(1.dp, ObsidianBorder),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = ObsidianBackground,
                    contentColor = PureWhite
                ),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.edit_creds_btn_auto),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1
                )
            }

            Button(
                onClick = { onSave(ssidTag, ssidName, passphrase) },
                enabled = supportsCustomCredentials && validation.isValid,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("credentials_save_button"),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PureWhite,
                    contentColor = ObsidianBackground,
                    disabledContainerColor = ObsidianSurfaceElevated,
                    disabledContentColor = SlateDim
                ),
                contentPadding = PaddingValues(horizontal = 12.dp)
            ) {
                Text(
                    text = if (isProxyRunning) {
                        stringResource(R.string.edit_creds_btn_save_apply)
                    } else {
                        stringResource(R.string.edit_creds_btn_save)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
