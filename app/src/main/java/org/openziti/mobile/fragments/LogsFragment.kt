/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */
package org.openziti.mobile.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.add
import androidx.fragment.app.commit
import org.openziti.mobile.R
import org.openziti.mobile.databinding.LogsBinding
import org.openziti.mobile.debug.DebugInfoActivity

class LogsFragment : BaseFragment() {

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
        return b.root
    }
}