/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.mobile

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.openziti.mobile.databinding.ActivityZitiEnrollmentBinding


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
/*
        enroll_opts.setOnClickListener {
            if (optsOpen) {
                hideEnrollOptions()
            } else {
                showEnrollOptions()
            }
        }
*/
        val scanLauncher = registerForActivityResult(ScanContract()) {
            if (it.contents == null) {
                Toast.makeText(this@ZitiEnrollmentActivity, "Cancelled", Toast.LENGTH_LONG).show()
            } else {
                enroll(it.contents)
            }
        }
        binding.QRButtonArea.setOnClickListener { qrEnroll(scanLauncher) }
        binding.QRButton.setOnClickListener { qrEnroll(scanLauncher) }

        val jwtLauncher = registerForActivityResult(StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) {
                it.data?.data?.let {uri ->
                    enroll(uri)
                }
            }
        }

        binding.JWTButton.setOnClickListener { jwtEnroll(jwtLauncher) }
        binding.IdButton.setOnClickListener { jwtEnroll(jwtLauncher) }

        binding.CloseIdentityButton.setOnClickListener {
            this.finish()
        }
    }

    fun jwtEnroll(launcher: ActivityResultLauncher<Intent>) {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        launcher.launch(intent)
    }

    fun qrEnroll(scanLauncher: ActivityResultLauncher<ScanOptions>) {
        val scanOptions = ScanOptions().apply {
            setDesiredBarcodeFormats(BarcodeFormat.QR_CODE.name)
            setPrompt("Scan enrollment code")
            setOrientationLocked(false)
            setCameraId(0)
        }
        scanLauncher.launch(scanOptions)
    }

    fun enroll(jwt: String) {
        (application as ZitiMobileEdgeApp).model.enroll(jwt)
            .handleAsync { _, ex ->
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

    fun enroll(jwtUri: Uri) {
        application.contentResolver.openInputStream(jwtUri)?.reader()?.use {
            enroll(it.readText())
            this.finish()
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
