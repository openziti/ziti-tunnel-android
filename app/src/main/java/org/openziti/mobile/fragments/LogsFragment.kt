/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */
package org.openziti.mobile.fragments

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.add
import androidx.fragment.app.commit
import androidx.transition.TransitionInflater
import org.openziti.mobile.R
import org.openziti.mobile.databinding.LogsBinding
import org.openziti.mobile.debug.DebugInfoActivity

class LogsFragment : Fragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inflater = TransitionInflater.from(requireContext())
        enterTransition = inflater.inflateTransition(R.transition.fade)
        exitTransition = inflater.inflateTransition(R.transition.slide)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = LogsBinding.inflate(inflater, container, false)
        b.BackLogsButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        b.PacketLogsButton.setOnClickListener {
            val intent = Intent(it.context, DebugInfoActivity::class.java)
            startActivity(intent)
        }

        b.ApplicationLogsButton.setOnClickListener {
            parentFragmentManager.commit {
                add<LogFragment>(R.id.fragment_container_view, "log",
                    args = bundleOf(LogFragment.LOG_TITLE to "Application Logs"))
                addToBackStack("log")
            }
        }

        // Inflate the layout for this fragment
        return b.root
    }
}