/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */
package org.openziti.mobile.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.transition.TransitionInflater
import org.openziti.mobile.R
import org.openziti.mobile.databinding.ConfigurationBinding

/**
 * A simple [Fragment] subclass.
 */
class ConfigFragment : Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inflater = TransitionInflater.from(requireContext())
        enterTransition = inflater.inflateTransition(R.transition.fade)
        exitTransition = inflater.inflateTransition(R.transition.slide)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ConfigurationBinding.inflate(inflater, container, false).apply {
            IPInput.text = ipAddress
            SubNetInput.text = subnet
            MTUInput.text = mtu
            DNSInput.text = dns

            BackConfigButton.setOnClickListener {
                parentFragmentManager.popBackStack()
            }
            BackConfigButton2.setOnClickListener {
                parentFragmentManager.popBackStack()
            }
        }.root

    companion object {
        val ipAddress = "100.64.0.1"
        val subnet = "255.255.0.0"
        val mtu = "4000"
        val dns = "100.64.0.2"
    }
}