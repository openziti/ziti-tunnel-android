/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.fragments

import android.os.Bundle
import android.util.Log
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

/**
 * A simple [Fragment] subclass.
 */
class IdentityDetailFragment : BaseFragment() {
    private val tunnel: TunnelModel by lazy {
        (requireActivity().application as ZitiMobileEdgeApp).model
    }
    val showRouters = mutableStateOf(false)
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
            else AuthenticationStatus.setOnClickListener(null)
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
        Log.i(this.javaClass.simpleName, "external auth completed: result[${result.resultCode}]")
        val message = when (result.resultCode) {
            AuthTabIntent.RESULT_OK -> "Received auth result."
            // ziti auth page closes on success
            AuthTabIntent.RESULT_CANCELED -> "AuthTab completed."
            AuthTabIntent.RESULT_VERIFICATION_FAILED -> "Verification failed."
            AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT -> "Verification timed out."
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