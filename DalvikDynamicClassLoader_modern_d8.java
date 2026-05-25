/* 现代化版本 (Android 8.0+ / API 26+)
 * 使用 d8 替代已弃用的 dx，使用 InMemoryDexClassLoader 完全免落盘
 *
 * EPL 1.0
 */
package clojure.lang;

import android.content.Context;
import android.util.Log;

import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.ByteDataView;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.origin.Origin;

import dalvik.system.InMemoryDexClassLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 现代化的 Android Dynamic ClassLoader（API 26+）：
 *   - 使用 d8 把 JVM bytecode 翻译为 DEX（替代已弃用的 dx）
 *   - 使用 InMemoryDexClassLoader 在内存中加载（替代落盘 + DexFile.loadDex）
 *
 * 无需 cacheDir、无需写临时文件、启动更快。
 */
public class DalvikDynamicClassLoader extends DynamicClassLoader {

    private static final String TAG = "DalvikClojureCompiler";
    private static Context applicationContext;

    public DalvikDynamicClassLoader() {
        super();
    }

    public DalvikDynamicClassLoader(final ClassLoader parent) {
        super(parent);
    }

    @Override
    protected Class<?> defineMissingClass(final String name, final byte[] bytes,
            final Object srcForm) {
        try {
            // 1. 用 d8 把 JVM bytecode 翻译为 DEX 字节
            final AtomicReference<byte[]> dexOut = new AtomicReference<>();

            D8.run(D8Command.builder()
                    .addClassProgramData(bytes, Origin.unknown())
                    .setMinApiLevel(26)
                    .setProgramConsumer(new DexIndexedConsumer() {
                        @Override
                        public void accept(int fileIndex, ByteDataView data,
                                           Set<String> descriptors,
                                           DiagnosticsHandler handler) {
                            dexOut.set(data.copyByteData());
                        }
                        @Override
                        public void finished(DiagnosticsHandler handler) {}
                    })
                    .build());

            byte[] dexBytes = dexOut.get();
            if (dexBytes == null) {
                throw new RuntimeException("d8 produced no DEX output for " + name);
            }

            // 2. 在内存中加载该 DEX，并 resolve 出我们想要的类
            ByteBuffer buf = ByteBuffer.wrap(dexBytes);
            InMemoryDexClassLoader dexLoader = new InMemoryDexClassLoader(buf, this);
            return dexLoader.loadClass(name);

        } catch (Exception e) {
            Log.e(TAG, "Failed to define class " + name, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public InputStream getDataReadersStream() {
        if (applicationContext != null) {
            try {
                return applicationContext.getAssets().open("data_readers.clj");
            } catch (IOException ignored) {}
        }
        return null;
    }

    public static void setContext(Context context) {
        applicationContext = context;
    }
}
