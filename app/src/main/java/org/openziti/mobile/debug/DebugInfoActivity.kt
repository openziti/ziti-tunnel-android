/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.debug

import android.os.Bundle
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import androidx.viewpager.widget.ViewPager
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import org.openziti.mobile.debug.SectionsPagerAdapter
import org.openziti.mobile.databinding.ActivityDebugInfoBinding
import org.openziti.util.DebugInfoProvider
import java.util.*

class DebugInfoActivity : AppCompatActivity() {

    internal val contentMap: List<Pair<String, DebugInfoProvider>>
    init {
        val loader = ServiceLoader.load(DebugInfoProvider::class.java)
        contentMap = loader.flatMap { l -> l.names().map{ it to l } }
    }

    private lateinit var binding: ActivityDebugInfoBinding
    private lateinit var sectionsPagerAdapter: SectionsPagerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDebugInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val viewPager: ViewPager = binding.viewPager
        viewPager.adapter = SectionsPagerAdapter(this, supportFragmentManager).apply {
            names = contentMap.map{ it.first }.toList()
        }

        val tabs: TabLayout = binding.tabs
        tabs.setupWithViewPager(viewPager)
    }

    internal fun getSectionProvider(section: String): DebugInfoProvider? {
        return contentMap.find { it.first == section }?.second
    }
}