/*
 * Copyright (c) 2025 NetFoundry. All rights reserved.
 */


package org.openziti.mobile.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.openziti.mobile.BuildConfig
import org.openziti.mobile.databinding.AboutBinding
import org.openziti.tunnel.Tunnel

/**
 * A simple [Fragment] subclass.
 * Use the [AboutFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class AboutFragment : BaseFragment() {
    private var _binding: AboutBinding? = null

    private val clipBoard by lazy {
        requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = AboutBinding.inflate(inflater, container, false)

        val b = _binding!!
        b.PrivacyButton.setOnClickListener {
            launchUrl("https://netfoundry.io/privacy-policy/")
        }
        b.TermsButton.setOnClickListener {
            launchUrl("https://netfoundry.io/terms/")
        }
        b.ThirdButton.setOnClickListener {
            launchUrl("https://netfoundry.io/third-party")
        }

        b.BackButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        b.Version.text = "${BuildConfig.VERSION_NAME}(${BuildConfig.GIT_COMMIT})"
        b.Version.setOnLongClickListener {
            val text =
                """Version:         ${BuildConfig.VERSION_NAME}(${BuildConfig.GIT_COMMIT})
                  |ziti-tunnel-sdk: ${Tunnel.zitiTunnelVersion()}
                  |ziti-sdk:        ${Tunnel.zitiSdkVersion()}
                  |tlsuv:           ${Tunnel.tlsuvVersion()}
            """.trimMargin()
            clipBoard.setPrimaryClip(
                ClipData.newPlainText("Versin Info", text)
            )
            true
        }
        return b.root
    }

    private fun launchUrl(url:String) {
        val openURL = Intent(Intent.ACTION_VIEW)
        openURL.data = Uri.parse(url)
        startActivity(openURL)
    }
}