import {
  activeSubscription,
  createEventDataCenter,
  destroySubscription,
  unActiveSubscription,
} from "./event-data-center";

type xcxOptions = { [key: string]: any };
declare function Page(options: any): void;
export default function customPage(
  options: xcxOptions,
  pageId: string,
  originPage: typeof Page = Page
) {
  if (!options) {
    throw new Error("options is required");
  }
  if (!pageId) {
    throw new Error("pageId is required");
  }
  
  originPage({
    ...options,
    onLoad(...arg: Array<xcxOptions>) {
      /** 如果有多个相同的页面，这里可以生成唯一的页面 id */
      this._pageId = createEventDataCenter(pageId);

      options.onLoad?.apply(this, arg);
    },

    /**
     * 组件卸载
     */
    onunload() {
      destroySubscription(this._pageId);
      options.onUnload?.apply(this);
    },

    /**
     * 页面隐藏
     */
    onHide() {
      unActiveSubscription();
      options.onHide?.apply(this);
    },

    /**
     * 页面显示
     */
    onShow() {
      activeSubscription(this._pageId);
      options.onShow?.apply(this);
    },
  });
}
