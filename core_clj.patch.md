# src/clj/clojure/core.clj 修改说明

新版 core.clj 中 `load-data-readers`（约第 8075 行）：

```clojure
(defn- load-data-readers []
  (alter-var-root #'*data-readers*
                  (fn [mappings]
                    (reduce load-data-reader-file
                            mappings (data-reader-urls)))))
```

在 Android 上，`data_readers.clj` 通常不在 classpath，而是放在 APK 的 assets/。
新版 DynamicClassLoader 已加 `getDataReadersStream()` 钩子（默认返回 null）。

**替换为**：

```clojure
(defn- load-data-readers []
  (alter-var-root #'*data-readers*
                  (fn [mappings]
                    (let [cl (.getContextClassLoader (Thread/currentThread))]
                      (if-let [stream (when (instance? clojure.lang.DynamicClassLoader cl)
                                        (.getDataReadersStream
                                          ^clojure.lang.DynamicClassLoader cl))]
                        (load-data-reader-file mappings stream)
                        (reduce load-data-reader-file
                                mappings (data-reader-urls)))))))
```

## 说明

- 在 JVM 上：`getDataReadersStream()` 返回 `null`，走原来的 classpath 枚举路径，行为完全一致。
- 在 Android 上：`DalvikDynamicClassLoader` 重写了该方法，从 `assets/data_readers.clj` 读。
- 接口上提到 `DynamicClassLoader`，所以 core.clj **不再 import Android 类**，
  能继续在 JVM 上 AOT 编译，互不污染。
