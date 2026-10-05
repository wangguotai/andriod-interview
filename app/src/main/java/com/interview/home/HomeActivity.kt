package com.interview.home

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.example.myapplication.R
import com.google.android.material.tabs.TabLayout

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 工程唯一首页 —— 底部 duotab，给所有功能 Activity 一个稳定入口。
 *
 * ─── 设计要点 ───
 *
 * 1. **单 Activity + 两个轻量 Fragment**：一栏一个 Fragment，切换只 show/hide，
 *    不重建列表 —— 来回切 tab 不丢滚动位置。
 * 2. **入口集中登记**：所有条目来自 [HomeCatalog]，这里不硬编码任何一个 Activity；
 *    新增功能页只改 HomeCatalog，首页零改动。
 * 3. **tab 与内容强绑定**：tab 文本、图标、Fragment 都由 [HomeCatalog.TABS] 同一份顺序驱动，
 *    不存在「加了 tab 忘了配列表」的错位。
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var fragments: List<Fragment>
    private var currentIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        fragments = HomeCatalog.TABS.map { HomeEntryFragment.newInstance(it) }

        val tabLayout = findViewById<TabLayout>(R.id.home_tab_layout)
        val icons = listOf(R.drawable.ic_tab_lab, R.drawable.ic_tab_basic)
        val containerId = R.id.home_container

        val tabTitles = HomeCatalog.TAB_TITLES
        val initialIndex = (savedInstanceState?.getInt(KEY_CURRENT_TAB, 0) ?: 0)
            .coerceIn(0, (fragments.size - 1).coerceAtLeast(0))

        tabLayout.removeAllTabs()
        tabTitles.forEachIndexed { index, title ->
            tabLayout.addTab(
                tabLayout.newTab()
                    .setText(title)
                    .setIcon(icons.getOrElse(index) { icons.last() })
            )
        }

        if (savedInstanceState == null) {
            // 首次进入：一次性 add 全部，但只让初始栏可见，其余先 hide，
            // 否则几个 Fragment 会短暂叠在同一个容器里。
            supportFragmentManager.beginTransaction().apply {
                fragments.forEachIndexed { index, fragment ->
                    add(containerId, fragment, "tab_$index")
                    if (index != initialIndex) hide(fragment)
                }
            }.commitNow()
            currentIndex = initialIndex
        } else {
            // 重建后 FragmentManager 已恢复旧实例与其可见性，用 tag 取回即可，
            // 不能再 add，否则重复添加会崩溃。
            fragments = tabTitles.indices.map { i ->
                supportFragmentManager.findFragmentByTag("tab_$i") ?: fragments[i]
            }
            currentIndex = initialIndex
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = switchTo(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        tabLayout.getTabAt(initialIndex)?.select()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CURRENT_TAB, currentIndex)
    }

    private fun switchTo(index: Int) {
        if (index == currentIndex || index !in fragments.indices) return
        supportFragmentManager.beginTransaction().apply {
            fragments.forEachIndexed { i, fragment ->
                if (i == index) {
                    show(fragment)
                } else {
                    hide(fragment)
                }
            }
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
        }.commit()
        currentIndex = index
    }

    companion object {
        private const val KEY_CURRENT_TAB = "home_current_tab"
    }
}
