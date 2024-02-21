# day: 2.21
1. 学习了 kotlin语法中
    inline、noinline、crossinline 的概念作用
2. ViewModel
   - 学习了ViewModel的概念，具有长生命周期 及 在Activity旋转保持 数据不变的原理 
     相关组件实现了ViewModelStoreOwner接口，在懒加载获取viewModel时，使用为ViewModelStoreOwner扩展的高级函数::viewModel<T>()，
     构建ViewModelProvider，通过provider在beanRegistry::definitions中查找对应ViewModel的BeanDefinition，
   - 源码的实现中使用Koin进行依赖注入，
     基本原理是：在Application的onCreate钩子函数调用时，调用startKoin创建ViewModel模块，
    