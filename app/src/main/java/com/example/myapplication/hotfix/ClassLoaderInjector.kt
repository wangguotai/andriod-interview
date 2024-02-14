package com.example.myapplication.hotfix

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import com.example.myapplication.hotfix.ShareReflectUtil.findField
import dalvik.system.DexFile
import dalvik.system.PathClassLoader
import java.io.File


/**
 * Time: 2024/2/11
 * Author: wgt
 * Description:
 */
class ClassLoaderInjector {
    companion object {
        /**
         * 创建一个可用于热修复的新加载器，将带修复的包放在头部，旧有的dex放在后面
         * 方法 1.先通过patches中的File的absolutePath收集代修复包
         *     2. 通过反射获取 pathList [DexPathList]-> dexElements -> 单个Element中的dexFile -> name 添加至dex字符串
         *     3. native中的so lib库，同样的反射方式 nativeLibraryDirectories [List<File>]
         *     4. 将dex的Path && native的Path 传入PathClassLoader
         * @param context
         * @param oldClassLoader
         * @param dispatchClassLoader
         * @param patches
         * @return
         */
        @Throws(Throwable::class)
        fun createNewClassLoader(
            context: Context,
            oldClassLoader: ClassLoader,
            dispatchClassLoader: ClassLoader,
            patches: List<File>
        ): ClassLoader {
            // 1. 得到pathList
            val pathListField = findField(oldClassLoader, "pathList")
            val oldPathList = pathListField.get(oldClassLoader)
            // 2. 得到dexElements
            val dexElementsField = findField(oldPathList, "dexElements")
            val oldDexElements: Array<Any> = dexElementsField.get(oldPathList) as Array<Any>
            // 3. 从Element 上得到dexFile
            val dexFileField = findField(oldDexElements[0], "dexFile")

            // 4. 获得原始的dexPath用于构造classloader
            // 将patches包中的dex文件放在头部
            val dexPathBuilder = StringBuilder()
            val packageName = context.packageName
            var isFirstItem = true
            for (patch in patches) {
                if (isFirstItem) {
                    isFirstItem = false
                } else {
                    dexPathBuilder.append(File.pathSeparator)
                }
                dexPathBuilder.append(patch.absolutePath)
            }
            for (oldDexElement in oldDexElements) {
                var dexPath: String? = null
                val dexFile = dexFileField.get(oldDexElement) as DexFile
                if (dexFile != null) {
                    dexPath = dexFile.name
                }
                if (dexPath.isNullOrEmpty()) {
                    continue
                }
                if (!dexPath.contains("/$packageName")) {
                    continue
                }
                if (!isFirstItem || dexPathBuilder.isNotEmpty()) {
                    dexPathBuilder.append(File.pathSeparator)
                } else {
                    isFirstItem = false
                }
                dexPathBuilder.append(dexPath)
            }
            val combinedDexPath = dexPathBuilder.toString()
            // 5. app的native库（so）文件目录 用于构造classLoader
            val nativeLibraryDirectoriesField =
                findField(oldPathList, "nativeLibraryDirectories")
            val oldNativeLibraryDirectories: List<File?> =
                nativeLibraryDirectoriesField.get(oldPathList) as List<File?>
            val libraryPathBuilder = StringBuilder()
            isFirstItem = true
            for (libDir in oldNativeLibraryDirectories) {
                if (libDir == null) {
                    continue
                }
                if (isFirstItem) {
                    isFirstItem = false
                } else {
                    libraryPathBuilder.append(File.pathSeparator)
                }
                libraryPathBuilder.append(libDir.absolutePath)
            }

            val combinedLibraryPath = libraryPathBuilder.toString()

            // 6. 创建自己的类加载器
            val result = PathClassLoader(combinedDexPath, combinedLibraryPath, dispatchClassLoader)
            return result
        }

        /**
         * 使用新的classLoader替换旧的
         * @param app
         * @param classLoader
         */
        @Throws(Throwable::class)
        private fun doInject(app: Application, classLoader: ClassLoader) {
            Thread.currentThread().contextClassLoader = classLoader
            val baseContext = findField(app, "mBase")[app] as Context
            val basePackageInfo =
                findField(baseContext, "mPackageInfo")[baseContext]
            findField(basePackageInfo, "mClassLoader").set(basePackageInfo, classLoader)
            if (Build.VERSION.SDK_INT < 27) {
                val res = app.resources
                try {
                    findField(res, "mClassLoader")[res] = classLoader
                    val drawableInflater = findField(res, "mDrawableInflater")[res]
                    if (drawableInflater != null) {
                        findField(drawableInflater, "mClassLoader")[drawableInflater] = classLoader
                    }
                } catch (ignored: Throwable) {
                    // Ignored.
                }
            }
        }

        /**
         * 热修复入口，创建一个新的classLoader
         * @param app
         * @param oldClassLoader
         * @param patches
         * @return
         */
        @Throws(Throwable::class)
        fun inject(
            app: Application,
            oldClassLoader: ClassLoader,
            patches: List<File>
        ): ClassLoader {
            // 分发加载任务的加载器，作为我们自己的加载器的父加载器
            val dispatchClassLoader = DispatchClassLoader(app.javaClass.name, oldClassLoader)
            // 创建我们自己的加载器
            val newClassLoader =
                createNewClassLoader(app, oldClassLoader, dispatchClassLoader, patches)
            dispatchClassLoader.setNewClassLoader(newClassLoader)
            doInject(app, newClassLoader)
            return newClassLoader
        }
    }

    /**
     * 进行classLoader的分发确定哪些使用旧的classLoader，哪些使用新的classLoader
     */
    private class DispatchClassLoader : ClassLoader {
        val TAG = javaClass.simpleName
        var mApplicationClassName: String
        var mOldClassLoader: ClassLoader
        lateinit var mNewClassLoader: ClassLoader
        val mCallFindClassOfLeafDirectly = object : ThreadLocal<Boolean>() {
            override fun initialValue(): Boolean {
                return false
            }
        }

        constructor(
            applicationClassName: String,
            oldClassLoader: ClassLoader
        ) : super(getSystemClassLoader()) {
            mApplicationClassName = applicationClassName
            mOldClassLoader = oldClassLoader
        }

        fun setNewClassLoader(classLoader: ClassLoader) {
            mNewClassLoader = classLoader
        }

        override fun findClass(name: String): Class<*>? {
            Log.d(TAG, "find $name")
            if (mCallFindClassOfLeafDirectly.get()) {
                return null
            }
            // 1. Application类不需要修复，使用原本的类加载器获得
            if (mApplicationClassName == name) {
                return findClass(mOldClassLoader, name)
            }
            // 2. 加载热修复框架的类 因为不需要修复的类，就用原本的类加载器获得
            if (name.startsWith("com.example.myapplication.hotfix.patch.")) {
                return findClass(mOldClassLoader, name)
            }

            try {
                return findClass(mNewClassLoader, name)
            } catch (ignored: ClassNotFoundException) {
                return findClass(mOldClassLoader, name)
            }
        }

        fun findClass(classLoader: ClassLoader, name: String): Class<*>? {
            try {
                // 双亲委托，所以可能会stackOverFlow死循环，防止这个情况
                mCallFindClassOfLeafDirectly.set(true)
                return classLoader.loadClass(name)
            } finally {
                mCallFindClassOfLeafDirectly.set(false)
            }
        }
    }


}