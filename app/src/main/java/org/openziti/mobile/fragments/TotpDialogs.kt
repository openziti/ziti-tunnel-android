/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.fragments

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import org.openziti.mobile.R
import org.openziti.tunnel.MfaEnrollment
import timber.log.Timber

/** The TOTP dialog being shown on the identity detail screen. */
sealed interface TotpDialog {
    /** enter the code that completes login */
    data object Submit : TotpDialog

    /** set up a new authenticator, then verify it with a code */
    data class Enroll(val enrollment: MfaEnrollment) : TotpDialog

    /** enter a code to view or replace the recovery codes */
    data object RecoveryAuth : TotpDialog

    data class RecoveryCodes(val codes: List<String>) : TotpDialog
}

/** A button that acts on the code typed into a [CodeDialog]. */
data class CodeAction(val label: String, val onClick: (code: String) -> Unit)

/**
 * A dialog with a code field and one button per [CodeAction]. The first action also runs from the keyboard.
 * Nothing can be dismissed while [busy], and [error] is shown under the field.
 */
@Composable
fun CodeDialog(
    title: String,
    actions: List<CodeAction>,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var code by remember { mutableStateOf("") }
    fun run(action: CodeAction?) {
        if (!busy && code.isNotBlank()) action?.onClick?.invoke(code.trim())
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                content()
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.totp_code_label)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    // recovery codes are accepted in place of a TOTP code, so letters must stay possible
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { run(actions.firstOrNull()) }),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text(stringResource(android.R.string.cancel))
                    }
                    actions.forEach { action ->
                        TextButton(onClick = { run(action) }, enabled = !busy && code.isNotBlank()) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}

/** QR code, authenticator link and secret for a pending enrollment. */
@Composable
fun ColumnScope.EnrollmentContent(enrollment: MfaEnrollment) {
    val context = LocalContext.current
    val qr = remember(enrollment.provisioningUrl) {
        runCatching {
            BarcodeEncoder().encodeBitmap(enrollment.provisioningUrl, BarcodeFormat.QR_CODE, 512, 512)
        }.onFailure { Timber.w(it, "failed to render enrollment QR code") }.getOrNull()
    }

    Text(stringResource(R.string.totp_setup_message), style = MaterialTheme.typography.bodyMedium)
    qr?.let {
        Image(
            bitmap = it.asImageBitmap(),
            contentDescription = stringResource(R.string.totp_qr_description),
            modifier = Modifier.size(200.dp).align(Alignment.CenterHorizontally)
        )
    }
    TextButton(
        onClick = {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, enrollment.provisioningUrl.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (ex: Exception) {
                Timber.i(ex, "no app to handle the provisioning URL")
                Toast.makeText(context, R.string.totp_no_authenticator, Toast.LENGTH_LONG).show()
            }
        },
        modifier = Modifier.align(Alignment.CenterHorizontally)
    ) {
        Text(stringResource(R.string.totp_open_authenticator))
    }
    enrollment.secret?.let { secret ->
        Text(stringResource(R.string.totp_secret), style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(secret, fontFamily = FontFamily.Monospace)
        }
        TextButton(onClick = { context.copySecret(secret) }) {
            Text(stringResource(R.string.totp_secret_copy))
        }
    }
}

@Composable
fun RecoveryCodesDialog(codes: List<String>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Column(
                modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    stringResource(R.string.recovery_codes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (codes.isEmpty()) {
                    Text(stringResource(R.string.totp_recovery_none))
                } else {
                    Text(stringResource(R.string.totp_recovery_message), style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            codes.forEach { Text(it, fontFamily = FontFamily.Monospace) }
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (codes.isNotEmpty()) {
                        TextButton(onClick = { context.copySecret(codes.joinToString("\n")) }) {
                            Text(stringResource(R.string.copy))
                        }
                    }
                    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
                }
            }
        }
    }
}

/** Copies [text] and asks the system not to echo it in the clipboard preview. */
private fun Context.copySecret(text: String) {
    val clip = ClipData.newPlainText("", text).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
    }
    getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    // Android 13+ confirms copies on its own
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, R.string.totp_copied, Toast.LENGTH_SHORT).show()
    }
}
