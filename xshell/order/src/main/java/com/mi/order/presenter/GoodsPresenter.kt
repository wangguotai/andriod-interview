package com.mi.order.presenter

import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.mi.order.bean.Goods
import com.mi.order.model.GoodsModel
import com.mi.order.model.IGoodsModel
import com.mi.order.model.IGoodsModel.OnLoadListener
import com.mi.order.view.IGoodsView
import com.wgt.lifecycle.presenter.BasePresenter

class GoodsPresenter<T : IGoodsView> : BasePresenter<T>() {
    var iGoodsModel: IGoodsModel? = GoodsModel()

    /**
     * 执行业务逻辑
     */
    fun fetch() {
        if (baseView != null && iGoodsModel != null) {
            iGoodsModel!!.loadGoodsData(object : OnLoadListener {
                override fun onComplete(goods: List<Goods>) {
                    (baseView!!.get() as IGoodsView?)!!.showGoodsView(goods)
                }

                override fun onError(msg: String) {}
            })
        }
    }

    override fun onCreateX(owner: LifecycleOwner?) {
        super.onCreateX(owner)
        Log.i("jett", "create")
    }

    override fun onDestory(owner: LifecycleOwner?) {
        super.onDestory(owner)
        Log.i("jett", "destroy")
    }
}
