# 让新版 Clojure (1.13+) 支持 Android / Dalvik 的方案

## 1. 问题诊断

对比 `/Users/xlisp/CljPro/clojure-android-1-7`（1.7 Android 分支）和 `/Users/xlisp/CljPro/clojure`（1.13.0-master-SNAPSHOT），根因在 4 处：

| 关注点 | 1.7 Android 分支 | 当前新版 (1.13) | 影响 |
|---|---|---|---|
| `DynamicClassLoader` | **抽象类**，把 bytecode→class 抽出成 `defineMissingClass` 钩子 | **具体类**，`defineClass` 直接调 JVM 的 `ClassLoader.defineClass` | ART/Dalvik 不认 JVM bytecode，启动后第一次 eval 即崩 |
| `JvmDynamicClassLoader` / `DalvikDynamicClassLoader` | 一对子类，平台选择 | 不存在 | 没有 Android 适配点 |
| `RT.makeClassLoader()` | 用 `VM_TYPE` 判断 Dalvik vs JVM，反射加载 `DalvikDynamicClassLoader` | 直接 `new DynamicClassLoader(...)` | 即使你单独编译了 `DalvikDynamicClassLoader`，Clojure 根本不会用它 |
| `load-data-readers`（core.clj） | 检查 ClassLoader 是 Dalvik 时从 assets 读 `data_readers.clj` | 只从 classpath URL 枚举读 | APK 里 classpath URL 枚举不稳定 |

## 2. 设计原则

**不照搬 1.7 把 `DynamicClassLoader` 重新变抽象** —— 那会破坏所有直接 `new DynamicClassLoader` 的下游库（leiningen、boot、tools.deps、shadow-cljs 全都中招）。

改成 **"钩子模式"**：

1. `DynamicClassLoader` 保持具体类，但 bytecode→class 拆出可重写方法 `defineMissingClass`，默认实现就是原本的 `defineClass(name, bytes, 0, bytes.length)`。**JVM 用户零影响**。
2. 同样把 `getDataReadersStream()` 加成返回 `null` 的钩子。
3. `DalvikDynamicClassLoader` 重写两个钩子，把 JVM bytecode → DEX（与 1.7 相同思路）放在 `defineMissingClass` 里。
4. `RT.makeClassLoader()` 在 `java.vm.name == "Dalvik"` 时**反射**创建 `DalvikDynamicClassLoader`，否则保持原行为。反射避免主 jar 依赖 android.jar/dx。
5. `core.clj` 的 `load-data-readers` 改为先问 ClassLoader 钩子，再 fallback 到 classpath 枚举。

整套对纯 JVM 用户**完全透明、零开销**，只在 Dalvik 上才激活分支。

## 3. 改动清单

```
clojure-android-patch/
├── DynamicClassLoader.java              # 替换 src/jvm/clojure/lang/DynamicClassLoader.java
├── DalvikDynamicClassLoader.java        # 新增到 src/jvm/clojure/lang/ (仅 Android 构建；dx 版本)
├── DalvikDynamicClassLoader_modern_d8.java  # 推荐！d8 + InMemoryDexClassLoader 版本 (API 26+)
├── RT.patch.md                          # src/jvm/clojure/lang/RT.java 修改步骤
├── core_clj.patch.md                    # src/clj/clojure/core.clj 修改步骤
└── README.md                            # 本文件
```

## 4. 应用步骤

### 步骤 A — 改造 Clojure 主仓库

```bash
cd /Users/xlisp/CljPro/clojure

# 1) 替换 DynamicClassLoader.java
cp /Users/xlisp/CljPro/clojure-android-patch/DynamicClassLoader.java \
   src/jvm/clojure/lang/DynamicClassLoader.java

# 2) 按 RT.patch.md 手工修改 src/jvm/clojure/lang/RT.java
#    (加 VM_TYPE 字段、改 makeClassLoader 方法)

# 3) 按 core_clj.patch.md 手工修改 src/clj/clojure/core.clj 中的 load-data-readers

# 4) 验证 JVM 构建照常通过
mvn -DskipTests package
mvn test  # 这一步证明改动对普通 JVM 用户透明
```

产物 `clojure-1.13.0-master-SNAPSHOT.jar` 在 JVM 上行为不变，但已经具备 **"如果跑在 Dalvik 上就反射去找 DalvikDynamicClassLoader"** 的能力。

### 步骤 B — Android 端打 supplementary jar

`DalvikDynamicClassLoader.java` 不能进入主 jar（它 import 了 `android.*` 和 `com.android.tools.r8.*`），单独打包：

```
clojure-android-support/
├── build.gradle
└── src/main/java/clojure/lang/
    └── DalvikDynamicClassLoader.java  ← 拷贝 _modern_d8.java（推荐）或原版
```

`build.gradle`:
```gradle
android {
    compileSdkVersion 34
    defaultConfig { minSdkVersion 26 }   // 用 _modern_d8 版本要求 ≥ 26
}
dependencies {
    compileOnly files('libs/clojure-1.13.0-android.jar')   // 步骤 A 的产物
    implementation 'com.android.tools:r8:8.2.47'           // d8/r8 翻译器
    // 如果用 dx 版本则改成：implementation 'com.android.tools:dx:1.16'
}
```

### 步骤 C — Android 应用集成

```java
public class MyApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 1. 给 DalvikDynamicClassLoader 设上下文
        DalvikDynamicClassLoader.setContext(this);
        // 2. 把 contextClassLoader 替换成 Dalvik 版本
        //    必须在第一次 require/load clojure 前完成
        Thread.currentThread().setContextClassLoader(
            new DalvikDynamicClassLoader(getClassLoader()));
        // 3. 触发 Clojure 静态初始化
        clojure.lang.RT.var("clojure.core", "require");
    }
}
```

`AndroidManifest.xml`:
```xml
<application
    android:name=".MyApp"
    android:largeHeap="true">
```

`data_readers.clj` 放到 `app/src/main/assets/data_readers.clj`，会被 `getDataReadersStream()` 自动加载。

## 5. 验证点

- **JVM 验证**：`mvn test` 全绿 → 改动对 JVM 透明。
- **Android 启动**：APK 内嵌 `clojure.lang.RT.var("clojure.core","+").invoke(1,2)` → 不抛异常即代表 ClassLoader 链路打通。
- **Android REPL**：在 APK 里走 `Compiler.eval(RT.readString("(+ 1 2)"))` → 返回 `3` 就说明 bytecode→DEX 链路正常。

## 6. 进阶说明

### 为什么有两个 DalvikDynamicClassLoader 版本？

| 文件 | 翻译器 | 加载器 | 目标 API | 适用场景 |
|---|---|---|---|---|
| `DalvikDynamicClassLoader.java` | dx (legacy) | DexFile.loadDex + 临时文件 | API ≥ 13 | 需兼容旧 Android，能复用 1.7 经验 |
| `DalvikDynamicClassLoader_modern_d8.java` | d8 (current) | InMemoryDexClassLoader | API ≥ 26 | **推荐**：免落盘、无 dx 依赖（已弃用） |

`com.android.tools:dx` 已经停止维护，最新可用版本是 `1.16`。推荐用 `_modern_d8`。

### 为什么用反射而不是直接 import？

`RT.java` 是 Clojure 的核心 RT，要保证它能在 JVM 上独立编译/AOT。如果 import `DalvikDynamicClassLoader`，主 jar 构建就必须依赖 `android.jar` 和 dx/d8 库，会污染所有 JVM 用户。反射方案：JVM 上零开销，Dalvik 上才解析。

### Fallback 路径的意义

`RT.makeClassLoader()` 里加了一层 try/catch：如果发现自己在 Dalvik 上但找不到 `DalvikDynamicClassLoader`，就降级返回普通 `DynamicClassLoader`。这样至少静态初始化能跑完，下游能拿到一个明确的运行时错误，而不是更糟的 `NoClassDefFoundError`。

## 7. 总结

**核心改动量**：
- 1 个文件替换（`DynamicClassLoader.java`，纯重构，无破坏性变更）
- 1 处方法替换 + 1 个字段新增（`RT.java`）
- 1 个 defn 替换（`core.clj` 的 `load-data-readers`）
- 1 个新文件（`DalvikDynamicClassLoader.java`，单独打包，不进主 jar）

**核心思想**：1.7 在 `DynamicClassLoader` 层用"抽象+继承"破坏了向下兼容；本方案用"具体+钩子"，向下兼容、向上可扩展，是更符合 Rich Hickey 设计美学的修法。
