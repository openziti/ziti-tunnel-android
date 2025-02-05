/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import org.openziti.mobile.LineView
import org.openziti.mobile.model.TunnelModel
import org.openziti.mobile.databinding.IdentityBinding
import org.openziti.mobile.model.Identity
import org.openziti.tunnel.JwtSigner

/**
 * A simple [Fragment] subclass.
 */
class IdentityDetailFragment : BaseFragment() {
    private val tunnel: TunnelModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = IdentityBinding.inflate(inflater, container, false).apply {
        val model = tunnel.identity(requireArguments().getString(ID)!!)!!
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
        model.controllers().observe(viewLifecycleOwner) {
            IdDetailsNetwork.text = it.firstOrNull() ?: "Unknown"
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
            model.setEnabled(state)
        }

        IdDetailsNetwork.setOnClickListener {
            getSystemService(requireContext(), ClipboardManager::class.java)?.apply {
                val clip = ClipData.newPlainText("Network", IdDetailsNetwork.text.toString())
                setPrimaryClip(clip)
                Toast.makeText(
                    requireContext(),
                    IdDetailsNetwork.text.toString() + " has been copied to your clipboard",
                    Toast.LENGTH_LONG
                ).show()
            }
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
    companion object {
        const val ID = "id"
    }

    private fun startJwtAuth(identity: Identity, provider: String?) {
        identity.useJWTSigner(provider).thenApply {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(it.url))
            startActivity(intent)
        }
    }
}