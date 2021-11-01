/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.debug

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import org.openziti.util.DebugInfoProvider
import java.util.*

/**
 * A [FragmentPagerAdapter] that returns a fragment corresponding to
 * one of the sections/tabs/pages.
 */
class SectionsPagerAdapter(private val context: Context, fm: FragmentManager) :
    FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

    private val contentMap: List<Pair<String, DebugInfoProvider>>
    init {
        val loader = ServiceLoader.load(DebugInfoProvider::class.java)
        contentMap = loader.flatMap { l -> l.names().map{ it to l } }
    }

    override fun getItem(position: Int): Fragment {
        val provider = contentMap[position]
        return DebugInfoFragment.newInstance(provider.first, provider.second)
    }

    override fun getPageTitle(position: Int): CharSequence = contentMap[position].first

    override fun getCount(): Int = contentMap.size
}