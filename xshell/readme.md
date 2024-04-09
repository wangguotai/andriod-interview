# xshell  作为组件化模块中的 壳工程

xshell中的 order和login 作为 子组件
common 作为基础库
mrouter-annotation 作为 模拟ARouter的 注解库
mrouter-api 作为 mrouter中处理path 和 group的关键

最终的效果：Group

```java
public class ARouter$$Group$$personal implements ARouterGroup {
    @Override
    public Map<String, Class<? extends ARouterPath>> getGroupMap() {
        Map<String, Class<? extends ARouterPath>> groupMap = new HashMap<>();
        groupMap.put("personal", ARouter$$Path$$personal.class);
        return groupMap;
    }
}
```

最终的效果：Path

```java
public class ARouter$$Path$$personal implements ARouterPath {
    @Override
    public Map<String, RouterBean> getPathMap() {
        Map<String, RouterBean> pathMap = new HashMap<>();
        pathMap.put("/personal/Personal_Main2Activity", RouterBean.create());
        pathMap.put("/personal/Personal_MainActivity", RouterBean.create());
        return pathMap;
    }
}
```

mrouter-compiler 作为注解处理程序库