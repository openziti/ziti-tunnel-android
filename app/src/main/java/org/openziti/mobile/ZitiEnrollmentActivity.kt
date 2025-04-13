/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.OrientationEventListener
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openziti.mobile.databinding.ActivityZitiEnrollmentBinding
import java.net.URL


class ZitiEnrollmentActivity : AppCompatActivity() {

    companion object {
        val TAG: String = ZitiEnrollmentActivity::class.java.simpleName
    }

    private lateinit var binding: ActivityZitiEnrollmentBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityZitiEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        intent.data?.let {
            enroll(it)
        }

        supportActionBar?.let { bar ->
            bar.setDisplayShowHomeEnabled(true)
            bar.setDisplayHomeAsUpEnabled(true)
        }

        val scanLauncher = registerForActivityResult(ScanContract()) {
            if (it.contents == null) {
                Toast.makeText(this@ZitiEnrollmentActivity, "Cancelled", Toast.LENGTH_LONG).show()
            } else {
                enroll(it.contents)
            }
        }

        val jwtLauncher = registerForActivityResult(StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                it.data?.data?.let {uri ->
                    enroll(uri)
                }
            }
        }

        binding.enrollmentContent.setContent {
            val urlDialog = remember { mutableStateOf(false) }
            if (urlDialog.value) {
                URLDialog { url ->
                    urlDialog.value = false
                    url?.let { enroll(it) } ?: Toast.makeText(this@ZitiEnrollmentActivity, "Cancelled", Toast.LENGTH_LONG).show()
                }
            }

            Column {
                EnrollmentTitle { this@ZitiEnrollmentActivity.finish() }

                EnrollmentCard(R.drawable.jwt, R.string.keyaction) {
                    jwtEnroll(jwtLauncher)
                }
                EnrollmentCard(R.drawable.qr, R.string.qraction) {
                    qrEnroll(scanLauncher)
                }
                EnrollmentCard(R.drawable.link, R.string.enroll_with_url) {
                    urlDialog.value = true
                }
            }
        }
    }

    private fun jwtEnroll(launcher: ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        launcher.launch(intent)
    }

    private fun qrEnroll(scanLauncher: ActivityResultLauncher<ScanOptions>) {
        val scanOptions = ScanOptions().apply {
            setDesiredBarcodeFormats(BarcodeFormat.QR_CODE.name)
            setPrompt("Scan enrollment code")
            setOrientationLocked(false)
            setCameraId(0)
        }
        scanLauncher.launch(scanOptions)
    }

    private fun enroll(jwt: String) {
        (application as ZitiMobileEdgeApp).model.enroll(jwt)
            .handleAsync { _, ex ->
                Log.i(TAG, "enroll result $ex")
                Handler(mainLooper).post {
                    this.finish()
                    if (ex != null) {
                        Toast.makeText(this, ex.localizedMessage, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this, "enrolled!", Toast.LENGTH_LONG).show()
                    }
                }

            }
    }

    private fun enroll(jwtUri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            application.contentResolver.openInputStream(jwtUri)?.reader()?.use {
                enroll(it.readText())
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

//    private fun showCertDialog() {
//        AlertDialog.Builder(this)
//                .setIcon(android.R.drawable.ic_secure)
//                .setTitle("Certificate required for enrollment")
//                .setNegativeButton(android.R.string.cancel) { _, _ -> }
//                .setPositiveButton("Select") { _, _ ->
//                    val launcher = registerForActivityResult(StartActivityForResult()) {
//                        if (it.resultCode == Activity.RESULT_OK) {
//                            it.data?.data?.let {
//                                Log.i(TAG, "cert $it")
//                                // TODO enrollWithCert(it)
//                            }
//                        }
//                    }
//                    val intent = Intent(Intent.ACTION_GET_CONTENT)
//                    intent.type = "application/x-pkcs12"
//
//                    launcher.launch(intent)
//                }
//                .show()
//    }
}

@Composable
fun EnrollmentTitle(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.End) {
        Text(
            stringResource(R.string.add_identity),
            textAlign = TextAlign.Center,
            style = TextStyle(
                color = colorResource(R.color.colorTitle),
                fontFamily = FontFamily(Font(R.font.russo_one)),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            ),
            modifier = Modifier
                .align(Alignment.CenterVertically)
                .weight(1.0F)
        )

        Image(
            painter = painterResource(R.drawable.close),
            contentDescription = stringResource(R.string.esc),
            modifier = Modifier
                .padding(8.dp)
                .align(Alignment.CenterVertically)
                .clickable { onClick() }
        )
    }
}

@Composable
fun EnrollmentCard(imageId: Int, textId: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.padding(4.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorResource(R.color.cardview_light_background)
        ),
        border = BorderStroke(1.dp, Color.Black),
        onClick = onClick
    ) {
        val modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
            .align(Alignment.CenterHorizontally)
        Image(
            modifier = modifier.size(100.dp),
            painter = painterResource(imageId),
            contentScale = ContentScale.Fit,
            contentDescription = stringResource(textId)
        )
        Text(
            stringResource(textId),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            style = TextStyle(
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun URLDialog(onConfirm: (str: String?) -> Unit) {
    val clippy = LocalClipboardManager.current
    var url by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.92f),
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        ),
        shape = RoundedCornerShape(20.dp),
        onDismissRequest = { onConfirm(null) },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
                enabled = url.isNotBlank() && !urlError
            ) {
                Text(text = "Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = { onConfirm(null) }) {
                Text(text = "Cancel")
            }
        },
        title = { Text(text = "Enter Controller URL", fontSize = 18.sp) },
        text = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {

                OutlinedTextField(
                    value = url,
                    onValueChange = { txt ->
                        runCatching { url = txt; URL(txt) }
                            .onSuccess { urlError = false }
                            .onFailure { urlError = true }
                    },
                    isError = urlError,
                    label = { Text("Controller URL") },
                    modifier = Modifier.weight(1F)
                )
                clippy.getText()?.let {
                    if (it.text.startsWith("https://")) {
                        TextButton(onClick = { url = it.text }) {
                            Text(text = "Paste URL\nyou copied")
                        }
                    }
                }
            }
        })
}

@Composable
@Preview
fun URLDialogPreview() {
    URLDialog{}
}
