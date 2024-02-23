# day: 2.21
1. 学习了 kotlin语法中
    inline、noinline、crossinline 的概念作用
2. ViewModel
   - 学习了ViewModel的概念，具有长生命周期 及 在Activity旋转保持 数据不变的原理 
     相关组件实现了ViewModelStoreOwner接口，在懒加载获取viewModel时，使用为ViewModelStoreOwner扩展的高级函数::viewModel<T>()，
     构建ViewModelProvider，通过provider在beanRegistry::definitions中查找对应ViewModel的BeanDefinition，
   - 源码的实现中使用Koin进行依赖注入，
     基本原理是：在Application的onCreate钩子函数调用时，调用startKoin创建ViewModel模块，
    
# day: 2.22 回溯了day21对于koin及ViewModel梳理回滚，RecycleView的布局（LayoutManager）和缓存（Recycler）   进行了一场面试
    面试中掌握不足的有 
     1. ANR 的概念，检测机制，线上发生ANR如何监控；
     2. 算法题： 根据前序遍历和后序遍历还原二叉树；
# day 2.23 先应付完工作， 在处理下算法题，之后看自定义View