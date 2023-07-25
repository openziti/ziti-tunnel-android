/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.debug

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * A [FragmentStateAdapter] that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 */
class SectionsPagerAdapter(act: FragmentActivity) : FragmentStateAdapter(act) {
    lateinit var names: List<String>
    override fun createFragment(position: Int): Fragment {
        val name = names[position]
        return DebugInfoFragment.newInstance(name)
    }

    override fun getItemCount(): Int = names.size
}