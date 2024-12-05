/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.fragments

import android.content.ClipData
import android.content.ClipboardManager
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
import org.openziti.mobile.TunnelModel
import org.openziti.mobile.databinding.IdentityBinding

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
            IdIdentityDetailName.text = n
        }
        model.enabled().observe(viewLifecycleOwner) {
            IdOnOffSwitch.isChecked = it
        }
        model.status().observe(viewLifecycleOwner) { st ->
            IdDetailsStatus.text = st
        }
        model.controller().observe(viewLifecycleOwner) {
            IdDetailsNetwork.text = it
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

    private fun forgetIdentity(model: TunnelModel.TunnelIdentity) {
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

    companion object {
        const val ID = "id"
    }
}