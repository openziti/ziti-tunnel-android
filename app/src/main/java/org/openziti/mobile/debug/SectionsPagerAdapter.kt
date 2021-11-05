/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.debug

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter

/**
 * A [FragmentPagerAdapter] that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 */
class SectionsPagerAdapter(private val context: Context, fm: FragmentManager) :
    FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

    lateinit var names: List<String>
    override fun getItem(position: Int): Fragment {
        val name = names[position]
        return DebugInfoFragment.newInstance(name)
    }

    override fun getPageTitle(position: Int): CharSequence = names[position]

    override fun getCount(): Int = names.size
}