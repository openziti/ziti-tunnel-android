/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.debug

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.openziti.mobile.databinding.ActivityDebugInfoBinding
import org.openziti.util.DebugInfoProvider
import java.util.ServiceLoader

class DebugInfoActivity : FragmentActivity() {

    internal val contentMap: List<Pair<String, DebugInfoProvider>>
    init {
        val loader = ServiceLoader.load(DebugInfoProvider::class.java)
        contentMap = loader.flatMap { l -> l.names().map{ it to l } }
    }

    private lateinit var binding: ActivityDebugInfoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDebugInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val viewPager = binding.viewPager
        val adapter = SectionsPagerAdapter(this).apply {
            names = contentMap.map{ it.first }.toList()
        }

        viewPager.adapter = adapter

        val tabs: TabLayout = binding.tabs
        TabLayoutMediator(tabs, viewPager) { tab, pos ->
            tab.text = adapter.names[pos]
        }.attach()
    }

    internal fun getSectionProvider(section: String): DebugInfoProvider? {
        return contentMap.find { it.first == section }?.second
    }
}