/*
 * Copyright (c) 2021 NetFoundry. All rights reserved.
 */

package org.openziti.mobile.debug

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import org.openziti.mobile.databinding.FragmentDebugInfoBinding
import org.openziti.util.DebugInfoProvider
import java.io.PrintWriter
import java.io.StringWriter

/**
 * A placeholder fragment containing a simple view.
 */
class DebugInfoFragment : Fragment() {

    private lateinit var pageViewModel: PageViewModel
    private var _binding: FragmentDebugInfoBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pageViewModel = ViewModelProvider(this).get(PageViewModel::class.java).apply {
            setText(arguments?.getString(ARG_SECTION_TEXT) ?: "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentDebugInfoBinding.inflate(inflater, container, false)
        val root = binding.root

        val textView: TextView = binding.sectionLabel
        textView.setHorizontallyScrolling(true)
        pageViewModel.text.observe(viewLifecycleOwner, { textView.text = it })
        return root
    }

    companion object {
        private const val ARG_SECTION_TEXT = "section_text"

        @JvmStatic
        fun newInstance(section: String, provider: DebugInfoProvider): DebugInfoFragment {
            val text = StringWriter()
            provider.runCatching { dump(section, text) }.onFailure {
                it.printStackTrace(PrintWriter(text))
            }
            return DebugInfoFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SECTION_TEXT, text.toString())
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}