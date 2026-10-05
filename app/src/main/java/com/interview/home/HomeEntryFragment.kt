package com.interview.home

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 首页某一栏的入口列表。参数用 [ARG_ENTRIES] 传入，宿主可随意复用。
 */
class HomeEntryFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val recycler = RecyclerView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            layoutManager = LinearLayoutManager(requireContext())
            setPadding(0, dp(8), 0, dp(8))
            clipToPadding = false
            itemAnimator = null
        }
        entryList().let { entries ->
            recycler.adapter = HomeEntryAdapter(entries) { entry ->
                startActivity(Intent(requireContext(), entry.activityClass))
            }
        }
        return recycler
    }

    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private fun entryList(): List<HomeEntry> =
        arguments?.getSerializable(ARG_ENTRIES) as? List<HomeEntry> ?: emptyList()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_ENTRIES = "arg_entries"

        fun newInstance(entries: List<HomeEntry>): HomeEntryFragment =
            HomeEntryFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_ENTRIES, ArrayList(entries))
                }
            }
    }
}
