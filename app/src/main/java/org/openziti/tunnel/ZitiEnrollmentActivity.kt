/*
 * Copyright (c) 2020 NetFoundry. All rights reserved.
 */

package org.openziti.tunnel

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.android.synthetic.main.activity_ziti_enrollment.*
import org.openziti.android.Ziti

class ZitiEnrollmentActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_LOAD_JWT = 10001
        const val REQUEST_LOAD_CERT = 10002
        val TAG = ZitiEnrollmentActivity::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ziti_enrollment)

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
        QRButtonArea.setOnClickListener {
            QrEnroll()
        }

        QRButton.setOnClickListener {
            QrEnroll()
        }

        JWTButton.setOnClickListener {
            JwtEnroll()
        }

        IdButton.setOnClickListener {
            JwtEnroll()
        }

        CloseIdentityButton.setOnClickListener {
            this.finish()
        }
    }

    fun JwtEnroll() {
        //hideEnrollOptions()
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_LOAD_JWT)
    }

    fun QrEnroll() {
        //hideEnrollOptions()
        IntentIntegrator(this).apply {
            setDesiredBarcodeFormats(BarcodeFormat.QR_CODE.name)
            setPrompt("Scan enrollment code")
            setOrientationLocked(false)
            setCameraId(0)
            initiateScan()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        when (requestCode) {
            REQUEST_LOAD_JWT -> {
                if (resultCode == Activity.RESULT_OK) {
                    intent?.data?.let {
                        enroll(it)
                    }
                }
            }

            IntentIntegrator.REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    val result = IntentIntegrator.parseActivityResult(resultCode, intent)
                    Ziti.enrollZiti(result.contents.toByteArray())
                }
            }

            REQUEST_LOAD_CERT -> {
                if (resultCode == Activity.RESULT_OK) {
                    intent?.data?.let {
                        Log.i(TAG, "cert $it")
                        // TODO enrollWithCert(it)
                    }
                }
            }
            else ->
                super.onActivityResult(requestCode, resultCode, intent)
        }
    }

    private lateinit var jwt: String

    fun enroll(jwtUri: Uri) {
        Log.i(TAG, "enrolling with $jwtUri")
        Ziti.enrollZiti(jwtUri)
    }

    override fun onSupportNavigateUp() = true.also { onBackPressed(); }

    private fun showCertDialog() {
        AlertDialog.Builder(this)
                .setIcon(android.R.drawable.ic_secure)
                .setTitle("Certificate required for enrollment")
                .setNegativeButton(android.R.string.cancel) { _, _ -> }
                .setPositiveButton("Select") { _, _ ->
                    val intent = Intent(Intent.ACTION_GET_CONTENT)
                    intent.type = "application/x-pkcs12"
                    startActivityForResult(intent, REQUEST_LOAD_CERT)
                }
                .show()
    }
}
