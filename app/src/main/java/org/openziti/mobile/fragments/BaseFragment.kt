/*
 * Copyright (c) 2024 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.fragments

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.transition.TransitionInflater
import org.openziti.mobile.R

open class BaseFragment: Fragment() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inflater = TransitionInflater.from(requireContext())
        enterTransition = inflater.inflateTransition(R.transition.fade)
        exitTransition = inflater.inflateTransition(R.transition.slide)
    }
}