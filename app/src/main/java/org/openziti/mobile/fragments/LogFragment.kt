/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Process
import android.text.method.ScrollingMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.transition.TransitionInflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.openziti.mobile.R
import org.openziti.mobile.databinding.LogBinding
import timber.log.Timber

/**
 * A simple [Fragment] subclass.
 */
class LogFragment : BaseFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = LogBinding.inflate(inflater, container, false).apply {
            arguments?.getString(LOG_TITLE)?.let {
                LogTypeTitle.text = it
            }

            BackToLogsButton.setOnClickListener {
                parentFragmentManager.popBackStack()
            }
            BackToLogsButton2.setOnClickListener {
                parentFragmentManager.popBackStack()
            }

            CopyLogButton.setOnClickListener {
                val clipboard = getSystemService(requireContext(), ClipboardManager::class.java)
                clipboard?.let {
                    val clip = ClipData.newPlainText("Logs", LogDetails.text.toString())

                    it.setPrimaryClip(clip)
                    Toast.makeText(
                        requireContext(),
                        "Log has been copied to your clipboard",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }


            LogDetails.movementMethod = ScrollingMovementMethod()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val p = Runtime.getRuntime().exec("logcat -d -t 200 --pid=${Process.myPid()}")
                val lines = p.inputStream.bufferedReader().readText()

                Timber.d("log is ${lines.length} bytes")

                LogDetails.post {
                    LogDetails.text = lines
                }
            }

        }

        return b.root
    }

    companion object {
            val LOG_TITLE = "log_title"
    }
}