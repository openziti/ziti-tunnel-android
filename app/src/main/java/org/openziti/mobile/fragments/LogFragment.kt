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
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.openziti.mobile.databinding.LogBinding
import timber.log.Timber
import java.util.concurrent.CompletableFuture

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
                val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
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
            val f = CompletableFuture.supplyAsync {
                val p = Runtime.getRuntime().exec("logcat -d -t 200 --pid=${Process.myPid()}")
                try {
                    p.inputStream.bufferedReader().use {
                        it.readText()
                    }
                } finally {
                    p.destroy()
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { f.await() }
                    .onSuccess {
                        Timber.d("log is ${it?.length} bytes")
                        LogDetails.text = it ?: "<empty log>"
                    }
                    .onFailure {
                        Timber.e(it, "failed to get logcat")
                        LogDetails.text = it.localizedMessage ?: "$it"
                    }
            }

        }

        return b.root
    }

    companion object {
            const val LOG_TITLE = "log_title"
    }
}