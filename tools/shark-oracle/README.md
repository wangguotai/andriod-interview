# shark 对拍基准（独立 oracle）

`real_dump_shark_classes.csv` 是给自研 hprof 解析器（`HeapHistogramParser`）做
**逐类对拍**用的独立基准，由 **shark 2.14** 生成 —— 与本实现没有任何共用代码。

## 为什么需要独立 oracle

自研解析器**不能只跟自己写的迷你 hprof 对拍**：迷你 hprof 是按"我以为的格式"造的，
格式理解错了，测试和实现会**一起错**、还一起通过。
（这正是本项目踩过的坑：第一版解析器在自己造的样例上"通过"，一上真实 dump 就
`ArrayIndexOutOfBoundsException` —— 9893 个 segment 里有我没料到的东西。）

## 重新生成

```bash
# 1) 取 shark-hprof（源码 jar 可同时用于核对格式）
curl -L -o shark-hprof-2.14.jar \
  https://repo1.maven.org/maven2/com/squareup/leakcanary/shark-hprof/2.14/shark-hprof-2.14.jar
curl -L -o kotlin-stdlib-1.8.20.jar \
  https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.8.20/kotlin-stdlib-1.8.20.jar
curl -L -o okio-jvm-3.4.0.jar \
  https://repo1.maven.org/maven2/com/squareup/okio/okio-jvm/3.4.0/okio-jvm-3.4.0.jar

# 2) 编译并跑
CP=shark-hprof-2.14.jar:kotlin-stdlib-1.8.20.jar:okio-jvm-3.4.0.jar
javac -cp "$CP" -d out Oracle.java
java -cp "$CP:out" Oracle /path/to/real.hprof > real_dump_shark_classes.csv
```

## ⚠️ 两个踩过的坑（在 Oracle.java 里已处理）

1. **`HprofHeader.parseHeaderOf` 是 `Companion` 上的**（Kotlin companion object），
   Java 里必须写 `HprofHeader.Companion.parseHeaderOf(file)`。
2. **不要把 `HEAP_DUMP_INFO` 放进 `readRecords` 的 tag 集合**：一旦放进去，shark 会把
   reader 交给监听器，而监听器**必须在该回调内把子记录读掉**；忘了读就会整段失步，
   报 `Unknown tag 0x00 at ... after 0xfe`（看着像文件坏了，其实是自己没读完）。
   让它留在集合外，shark 内部会自己 skip —— 这个坑本身也印证了
   `0xFE` 是解析器最容易失步的地方。
