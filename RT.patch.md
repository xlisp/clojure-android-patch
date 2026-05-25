# RT.java 修改说明

新版 RT.java 中 makeClassLoader() 无条件返回 new DynamicClassLoader(...)。
我们让它在 Dalvik / ART 上反射加载 DalvikDynamicClassLoader。

文件路径: `src/jvm/clojure/lang/RT.java`

## [1] 加 import（如果还没有）

```java
import java.lang.reflect.Constructor;
```

## [2] 加 VM_TYPE 字段

放在已有的 `static Keyword LINE_KEY = ...` 附近，或任何 static 字段区：

```java
static Keyword DALVIK_VM = Keyword.intern(null, "dalvik-vm");
static Keyword JAVA_VM   = Keyword.intern(null, "java-vm");
final static public Var VM_TYPE =
        Var.intern(CLOJURE_NS, Symbol.create("vm-type"),
                   "Dalvik".equals(System.getProperty("java.vm.name"))
                     ? DALVIK_VM : JAVA_VM);
```

## [3] 替换 makeClassLoader()

**原来的（新版当前代码）**：

```java
static public ClassLoader makeClassLoader(){
    return (ClassLoader) AccessController.doPrivileged(new PrivilegedAction(){
        public Object run(){
            try{
                Var.pushThreadBindings(RT.map(USE_CONTEXT_CLASSLOADER, RT.T));
                return new DynamicClassLoader(baseLoader());
            }
            finally{
                Var.popThreadBindings();
            }
        }
    });
}
```

**替换为**：

```java
static public ClassLoader makeClassLoader(){
    return (ClassLoader) AccessController.doPrivileged(new PrivilegedAction(){
        public Object run(){
            try{
                Var.pushThreadBindings(RT.map(USE_CONTEXT_CLASSLOADER, RT.T));
                if(VM_TYPE.deref() == DALVIK_VM) {
                    try {
                        final Class<?> loaderClass =
                            Class.forName("clojure.lang.DalvikDynamicClassLoader");
                        final Constructor<?> constructor =
                            loaderClass.getConstructor(ClassLoader.class);
                        return constructor.newInstance(baseLoader());
                    } catch(Exception e) {
                        // Fallback: 普通 DynamicClassLoader 也能至少完成静态初始化，
                        // 直到第一次需要 defineClass 才会 fail。比 NoClassDefFoundError 友好。
                        return new DynamicClassLoader(baseLoader());
                    }
                } else {
                    return new DynamicClassLoader(baseLoader());
                }
            }
            finally{
                Var.popThreadBindings();
            }
        }
    });
}
```

## 设计说明

- **反射 Class.forName 而非 import** —— 这样普通 JVM 上构建 clojure.jar 时不依赖
  android.jar 和 dx 库；只有运行在 Dalvik 上时才会尝试解析。
- **`java.vm.name == "Dalvik"` 是 Android 上稳定的检测方式**，从 Android 1.0 到
  现在的 ART 都一直返回 "Dalvik"。
- **fallback 路径** —— 万一 DalvikDynamicClassLoader 不在 classpath，至少
  Clojure 静态初始化阶段不会立即崩，能给出更有用的 stack trace。
