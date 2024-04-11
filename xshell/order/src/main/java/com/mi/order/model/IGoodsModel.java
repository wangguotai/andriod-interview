package com.mi.order.model;


import com.mi.order.bean.Goods;

import java.util.List;

public interface IGoodsModel {
    void loadGoodsData(OnLoadListener onLoadListener);

    interface OnLoadListener {
        void onComplete(List<Goods> goods);

        void onError(String msg);
    }
}









