/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import org.openziti.mobile.LineView
import org.openziti.mobile.R
import org.openziti.mobile.ZitiMobileEdgeApp
import org.openziti.mobile.databinding.IdentityBinding
import org.openziti.mobile.model.Identity
import org.openziti.mobile.model.TunnelModel
import org.openziti.tunnel.JwtSigner
import org.openziti.tunnel.RouterEvent
import org.openziti.tunnel.RouterStatus
import timber.log.Timber
import java.util.concurrent.CompletableFuture

/**
 * A simple [Fragment] subclass.
 */
class IdentityDetailFragment : BaseFragment() {
    private val tunnel: TunnelModel by lazy {
        (requireActivity().application as ZitiMobileEdgeApp).model
    }
    val showRouters = mutableStateOf(false)
    private val totpDialog = mutableStateOf<TotpDialog?>(null)
    private val totpBusy = mutableStateOf(false)
    private val totpError = mutableStateOf<String?>(null)
    lateinit var model: Identity

    override fun onResume() {
        super.onResume()
        model.refresh()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = IdentityBinding.inflate(inflater, container, false).apply {
        model = tunnel.identity(requireArguments().getString(ID)!!)!!
        model.refresh()

        BackIdentityDetailsButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        model.name().observe(viewLifecycleOwner) { n ->
            if (!n.isNullOrEmpty()) {
                IdIdentityDetailName.text = n
            }
        }
        model.enabled().observe(viewLifecycleOwner) {
            IdOnOffSwitch.isChecked = it
        }
        model.authState().observe(viewLifecycleOwner) { authState ->
            AuthenticationStatus.text = authState.label
            if (authState is Identity.AuthJWT) {
                if (authState.providers.size > 1) {
                    AuthenticationStatus.setOnClickListener {
                        showJWTSelect(model, authState.providers)
                    }
                } else {
                    AuthenticationStatus.setOnClickListener {
                        startJwtAuth(model, authState.providers.firstOrNull()?.name)
                    }
                }
            }
            else if (authState is Identity.AuthTOTP) {
                AuthenticationStatus.setOnClickListener { showTotp(TotpDialog.Submit) }
            }
            else if (authState is Identity.EnrollTOTP) {
                AuthenticationStatus.setOnClickListener { startTotpEnrollment() }
            }
            else AuthenticationStatus.setOnClickListener(null)
        }

        RecoveryCodesRow.setOnClickListener {
            showTotp(TotpDialog.RecoveryAuth)
        }
        model.status().observe(viewLifecycleOwner) { st ->
            IdDetailsStatus.text = st
        }
        var sCount = 0
        model.services().observe(viewLifecycleOwner) { serviceList ->
            IdDetailServicesList.removeAllViews()
            for (service in serviceList) {
                sCount++
                val line = LineView(requireContext())
                line.label = service.name
                line.value = service.interceptConfig
                IdDetailServicesList.addView(line)
            }
        }

        IdOnOffSwitch.setOnCheckedChangeListener { _, state ->
            if (state != model.enabled().value)
                model.setEnabled(state)
        }

        composeView.setContent {
            if (showRouters.value) {
                RouterDialog(model.routers().observeAsState(), showRouters)
            }
            totpDialog.value?.let { TotpDialogHost(it) }
        }

        model.routers().observe(viewLifecycleOwner) {
            val router = it.values.firstOrNull { rt -> rt.status == RouterStatus.CONNECTED }
            IdDetailsNetwork.text = router?.status?.name ?: RouterStatus.DISCONNECTED.name
        }

        IdDetailsNetwork.setOnClickListener {
            showRouters.value = true
        }

        IdDetailForgetButton.setOnClickListener {
            forgetIdentity(model)
        }

    }.root

    private fun showTotp(dialog: TotpDialog) {
        totpBusy.value = false
        totpError.value = null
        totpDialog.value = dialog
    }

    private fun closeTotp() {
        totpDialog.value = null
        totpBusy.value = false
        totpError.value = null
    }

    // command futures complete on the native tunnel thread
    private fun onUi(block: () -> Unit) {
        activity?.runOnUiThread(block)
    }

    private fun toast(message: String) {
        context?.let { Toast.makeText(it, message, Toast.LENGTH_LONG).show() }
    }

    /** Runs a TOTP command for the open dialog: shows progress, and the failure reason if it fails. */
    private fun <T> runTotp(call: () -> CompletableFuture<T>, onSuccess: (T) -> Unit) {
        val fallback = getString(R.string.totp_failed)
        totpBusy.value = true
        totpError.value = null
        call().whenComplete { result, ex ->
            onUi {
                totpBusy.value = false
                if (ex != null) {
                    val reason = ex.cause?.message ?: ex.message ?: fallback
                    Timber.w("TOTP request failed: $reason")
                    totpError.value = reason
                } else {
                    onSuccess(result)
                }
            }
        }
    }

    private fun startTotpEnrollment() {
        val fallback = getString(R.string.totp_failed)
        model.enrollMFA().whenComplete { enrollment, ex ->
            onUi {
                if (ex != null) {
                    toast(ex.cause?.message ?: ex.message ?: fallback)
                } else {
                    showTotp(TotpDialog.Enroll(enrollment))
                }
            }
        }
    }

    private fun forgetIdentity(model: Identity) {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Confirm")
        builder.setMessage("Are you sure you want to delete this identity from your device?")
        builder.setIcon(android.R.drawable.ic_dialog_alert)

        builder.setPositiveButton("Yes") { _, _ ->
            model.delete()
            Toast.makeText(
                requireContext(),
                model.name().value + " removed",
                Toast.LENGTH_LONG
            ).show()
            parentFragmentManager.popBackStack()
        }

        builder.setNeutralButton("Cancel") { _, _ -> }

        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    private fun showJWTSelect(identity: Identity, providers: List<JwtSigner>) {
        val names = providers.map { it.name }.toTypedArray()
        val builder =
            AlertDialog.Builder(requireContext())
                .setTitle("Select External Login")
                .setIcon(android.R.drawable.ic_dialog_dialer)
                .setNegativeButton("Cancel") { _, _ -> }
                .setItems(names) { _, which ->
                    startJwtAuth(identity, providers[which].name)
                }
        builder.create().show()
    }
    private val launcher = AuthTabIntent.registerActivityResultLauncher(this, this::onAuthResult)
    private fun onAuthResult(result: AuthTabIntent.AuthResult) {
        Timber.i("external auth completed: result[${result.resultCode}]")
        val message = when (result.resultCode) {
            AuthTabIntent.RESULT_OK -> "Authentication completed"
            // ziti auth page closes on success
            AuthTabIntent.RESULT_CANCELED -> "Authentication completed"
            AuthTabIntent.RESULT_VERIFICATION_FAILED -> "Verification failed"
            AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT -> "Verification timed out"
            else -> "Unknown result code: ${result.resultCode}"
        }

        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun startJwtAuth(identity: Identity, provider: String?) {
        identity.useJWTSigner(provider).thenApply {
            AuthTabIntent.Builder().build().apply {
                launch(launcher, it.url.toUri(), "ziti+auth")
            }
        }
    }

    @Composable
    private fun TotpDialogHost(dialog: TotpDialog) {
        val busy = totpBusy.value
        val error = totpError.value
        when (dialog) {
            is TotpDialog.Submit -> {
                val accepted = stringResource(R.string.totp_code_accepted)
                CodeDialog(
                    title = stringResource(R.string.totp_code_title),
                    actions = listOf(CodeAction(stringResource(R.string.totp_submit)) { code ->
                        runTotp({ model.submitMFA(code) }) {
                            closeTotp()
                            toast(accepted)
                        }
                    }),
                    busy = busy,
                    error = error,
                    onDismiss = ::closeTotp,
                )
            }

            is TotpDialog.Enroll -> CodeDialog(
                title = stringResource(R.string.totp_setup_title),
                actions = listOf(CodeAction(stringResource(R.string.totp_verify)) { code ->
                    runTotp({ model.verifyMFA(code) }) {
                        // the recovery codes are only handed out with the enrollment
                        val codes = dialog.enrollment.recoveryCodes.orEmpty()
                        if (codes.isEmpty()) closeTotp() else showTotp(TotpDialog.RecoveryCodes(codes))
                    }
                }),
                busy = busy,
                error = error,
                onDismiss = ::closeTotp,
            ) {
                EnrollmentContent(dialog.enrollment)
            }

            is TotpDialog.RecoveryAuth -> CodeDialog(
                title = stringResource(R.string.recovery_codes),
                actions = listOf(
                    CodeAction(stringResource(R.string.totp_recovery_view)) { code ->
                        runTotp({ model.recoveryCodes(code) }) {
                            showTotp(TotpDialog.RecoveryCodes(it.recoveryCodes.orEmpty()))
                        }
                    },
                    CodeAction(stringResource(R.string.totp_recovery_new)) { code ->
                        runTotp({ model.generateRecoveryCodes(code) }) {
                            showTotp(TotpDialog.RecoveryCodes(it.recoveryCodes.orEmpty()))
                        }
                    },
                ),
                busy = busy,
                error = error,
                onDismiss = ::closeTotp,
            ) {
                Text(stringResource(R.string.totp_recovery_auth_message))
            }

            is TotpDialog.RecoveryCodes -> RecoveryCodesDialog(dialog.codes, onDismiss = ::closeTotp)
        }
    }

    @Composable
    fun RouterDialog(model: State<Map<String, RouterEvent>?>, show: MutableState<Boolean> = mutableStateOf(true)) {
        if (show.value) {
            Dialog(
                properties = DialogProperties(
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true
                ),
                onDismissRequest = { show.value = false }) {
                Card(modifier = dialogModifier) {
                    Row(modifier = Modifier.fillMaxWidth().height(20.dp) ) {
                        Icon(
                            painter = painterResource(id = R.drawable.z),
                            contentDescription = "Routers",
                            modifier = Modifier.fillMaxHeight().padding(8.dp)
                        )
                        Text(
                            text = "Routers",
                            modifier = Modifier.fillMaxWidth(),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                    Column {
                        model.value?.forEach { (_, router) ->
                            Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                Text(router.name,
                                    modifier = Modifier.fillMaxWidth(0.6f),
                                    softWrap = true,
                                    fontWeight = FontWeight.Bold)
                                Text(
                                    router.status.name,
                                    textAlign = TextAlign.End,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        val dialogModifier = Modifier
            .fillMaxWidth()
            .height(375.dp)
            .padding(16.dp)
        const val ID = "id"
    }
}