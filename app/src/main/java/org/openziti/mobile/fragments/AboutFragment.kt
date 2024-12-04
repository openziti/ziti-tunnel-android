/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */


package org.openziti.mobile.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.transition.TransitionInflater
import org.openziti.mobile.BuildConfig
import org.openziti.mobile.R
import org.openziti.mobile.databinding.AboutBinding

/**
 * A simple [Fragment] subclass.
 * Use the [AboutFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class AboutFragment : Fragment() {
    private var _binding: AboutBinding? = null

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
        return b.root
    }

    private fun launchUrl(url:String) {
        val openURL = Intent(Intent.ACTION_VIEW)
        openURL.data = Uri.parse(url)
        startActivity(openURL)
    }
}