package com.mi.order.view;


import com.mi.order.bean.Goods;
import com.wgt.lifecycle.presenter.interf.view.IBaseView;

import java.util.List;

/**
 * UI逻辑
 */
public interface IGoodsView extends IBaseView {
    //显示图片
    void showGoodsView(List<Goods> goods);

    //加载进度条
    //加载动画
    //.......
}









