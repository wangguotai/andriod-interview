import {
  subscribe,
  getModelData,
  dispatch,
} from "../../status-manager/src/event-data-center/event-data-center";
Component({
  properties: {
    pageType: {
      type: String,
    },
  },
  data: {
    count: 0,
  },
  lifetimes: {
    ready() {
      /* 进行订阅 */
      this.unSubscribe = subscribe(() => {
        //获取购物车数量
        const count = getModelData("count");
        if (count !== this.data.count) {
          console.log(`底部购物车,更新来源于${this.properties.pageType}`);
          this.setData({
            count,
          });
        }
      });
      const count = getModelData("count");
      if (count) {
        this.setData({ count });
      }
    },
    detached() {
      /* 取消订阅 */
      typeof this.unSubscribe === "function" && this.unSubscribe();
    },
  },
  methods: {
    /* 加购操作*/
    addCart() {
      const count = getModelData("count") || 0;
      dispatch({
        count: count + 1,
      });
    },
  },
});
