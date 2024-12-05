/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */
package org.openziti.mobile.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.add
import androidx.fragment.app.commit
import org.openziti.mobile.R
import org.openziti.mobile.databinding.AdvancedBinding

/**
 * A simple [Fragment] subclass.
 */
class AdvancedFragment : BaseFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = AdvancedBinding.inflate(inflater, container, false).apply {
        BackAdvancedButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        TunnelButton.setOnClickListener {
            parentFragmentManager.commit {
                add<ConfigFragment>(R.id.fragment_container_view, "config")
                addToBackStack("config")
            }
        }
        LogsButton.setOnClickListener {
            parentFragmentManager.commit {
                add<LogsFragment>(R.id.fragment_container_view, "logs")
                addToBackStack("logs")
            }
        }
    }.root
}