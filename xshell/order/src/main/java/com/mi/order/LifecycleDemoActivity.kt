package com.mi.order

import android.os.Bundle
import android.util.Log
import android.widget.ListView
import androidx.lifecycle.LifecycleObserver
import com.mi.order.adapter.GoodsAdapter
import com.mi.order.bean.Goods
import com.mi.order.databinding.LayoutLifecycleDemoBinding
import com.mi.order.databus.LiveDataBus
import com.mi.order.presenter.GoodsPresenter
import com.mi.order.view.IGoodsView
import com.mi.router_annotation.MRouter
import com.wgt.base.BaseActivity

/**
 * Time: 2024/4/12
 * Author: wgt
 * Description:
 */
@MRouter(path = "/order/LifecycleDemoActivity")
class LifecycleDemoActivity :
    BaseActivity<LayoutLifecycleDemoBinding, IGoodsView, GoodsPresenter<IGoodsView>>() {


    private var listView: ListView? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        listView = binding.listView
        presenter.fetch()
        LiveDataBus.getInstance().with("list", ArrayList::class.java)
            .observe(this) { arrayList ->
                if (arrayList != null) {
                    Log.i("jett", "收到了数据$arrayList")
                }
            }
    }

    override fun createPresenter(): GoodsPresenter<IGoodsView> {
        return GoodsPresenter()
    }

    override fun getLayoutId(): Int = R.layout.layout_lifecycle_demo

    fun showGoodsView(goods: List<Goods?>?) {
        listView!!.adapter = GoodsAdapter(this, goods)
    }

    fun showErrorMessage(msg: String?) {}

    override fun init() {
        super.init()
        lifecycle.addObserver(presenter as LifecycleObserver)
    }
}