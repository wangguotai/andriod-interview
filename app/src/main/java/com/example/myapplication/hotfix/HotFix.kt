package com.example.myapplication.hotfix

import android.app.Application
import android.os.Build
import android.util.Log
import com.example.myapplication.hotfix.ShareReflectUtil.findField
import com.example.myapplication.hotfix.ShareReflectUtil.findMethod
import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException


class HotFix {
    companion object {
        val TAG = "hotfix.HotFix"

        fun installPatch(application: Application, patch: File) {
            // 1. 获取classLoader [PathClassLoader]
            val classLoader = application.classLoader
            val files = mutableListOf<File>()
            if (patch.exists()) {
                files.add(patch)
            }
            val dexOptDir = application.cacheDir
            // Android 7.0 target 24
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
//                    ClassLoaderInjector.inject(application, classLoader, files)

                    V23.install(classLoader, files, dexOptDir, application)


                } catch (throwable: Throwable) {
                    Log.d(TAG, throwable.message ?: "")
                    throwable.printStackTrace()
                }
            } else {
                try {
                    // 23 6.0及以上
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                        V23.install(classLoader, files, dexOptDir)
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        // 19 4.4以上
                        V19.install(classLoader, files, dexOptDir)
                    } else {
                        // ignore for same code
                        // >=14
//                        V14.install()
                    }
                }catch (e: Exception) {
                    e.printStackTrace()
                }
            }

        }
    }

    private object V23 {
        @Throws(
            IllegalArgumentException::class,
            IllegalAccessException::class,
            NoSuchFieldException::class,
            InvocationTargetException::class,
            NoSuchMethodException::class,
            IOException::class
        )
        fun install(
            loader: ClassLoader,
            additionalClassPathEntries: List<File>,
            optimizedDirectory: File,
            application: Application
        ) {
            //找到 pathList
            val pathListField: Field = findField(loader, "pathList")
            val dexPathList: Any = pathListField.get(loader)
            val suppressedExceptions = ArrayList<IOException>()
            // 从 pathList找到 makePathElements 方法并执行
            // 得到补丁创建的 Element[]
            val patchElements = makePathElements(
                dexPathList,
                ArrayList(additionalClassPathEntries), optimizedDirectory,
                suppressedExceptions
            )

            //将原本的 dexElements 与 makePathElements生成的数组合并
//            val resultDexElements =  ShareReflectUtil.expandFieldArray(dexPathList, "dexElements", patchElements)
            ShareReflectUtil.expandFieldArray(dexPathList, "dexElements", patchElements)

//            val dispatchClassLoader =
//                ClassLoaderInjector.DispatchClassLoader(application.javaClass.name, loader)
//            val newClassLoader = ClassLoaderInjector.createNewClassLoader(application, loader, dispatchClassLoader, additionalClassPathEntries)
            //找到 pathList
//            val newPathListField: Field = findField(newClassLoader, "pathList")
//            val newPathList = newPathListField.get(newClassLoader)
//            val dexElementsField = findField(newPathList, "dexElements")
//            // 获取新ClassLoader的dexElement，并替换
//            var dexElements = dexElementsField.get(newPathList)
//            dexElements = resultDexElements
            // 从 pathList找到 makePathElements 方法并执行
            // 得到补丁创建的 Element[]
//            ClassLoaderInjector.doInject(application, newClassLoader)
            if (suppressedExceptions.size > 0) {
                for (e in suppressedExceptions) {
                    Log.w(TAG, "Exception in makePathElement", e)
                    throw e
                }
            }
        }

        /**
         * 把dex转化为Element数组
         */
        @Throws(
            IllegalAccessException::class,
            InvocationTargetException::class,
            NoSuchMethodException::class
        )
        private fun makePathElements(
            dexPathList: Any, files: ArrayList<File>, optimizedDirectory: File,
            suppressedExceptions: ArrayList<IOException>
        ): Array<*> {
            //通过阅读android6、7、8、9源码，都存在makePathElements方法
            val makePathElements = findMethod(
                dexPathList, "makePathElements",
                MutableList::class.java, File::class.java,
                MutableList::class.java
            )
            return makePathElements.invoke(
                dexPathList, files, optimizedDirectory,
                suppressedExceptions
            ) as Array<*>
        }
    }


    private object V19 {
        @Throws(
            IllegalArgumentException::class,
            IllegalAccessException::class,
            NoSuchFieldException::class,
            InvocationTargetException::class,
            NoSuchMethodException::class,
            IOException::class
        )
        fun install(
            loader: ClassLoader,
            additionalClassPathEntries: List<File>,
            optimizedDirectory: File
        ) {
            val pathListField: Field = findField(loader, "pathList")
            val dexPathList: Any = pathListField.get(loader)
            val suppressedExceptions = ArrayList<IOException>()
            ShareReflectUtil.expandFieldArray(
                dexPathList, "dexElements",
                makeDexElements(
                    dexPathList,
                    ArrayList<File>(additionalClassPathEntries), optimizedDirectory,
                    suppressedExceptions
                )
            )
            if (suppressedExceptions.size > 0) {
                for (e in suppressedExceptions) {
                    Log.w(TAG, "Exception in makeDexElement", e)
                    throw e
                }
            }
        }

        @Throws(
            IllegalAccessException::class,
            InvocationTargetException::class,
            NoSuchMethodException::class
        )
        private fun makeDexElements(
            dexPathList: Any, files: ArrayList<File>, optimizedDirectory: File,
            suppressedExceptions: ArrayList<IOException>
        ): Array<Any> {
            val makeDexElements = findMethod(
                dexPathList, "makeDexElements",
                ArrayList::class.java, File::class.java,
                ArrayList::class.java
            )
            return makeDexElements.invoke(
                dexPathList, files, optimizedDirectory,
                suppressedExceptions
            ) as Array<Any>
        }
    }


    /**
     * 14, 15, 16, 17, 18.
     */
    private object V14 {
        @Throws(
            IllegalArgumentException::class,
            IllegalAccessException::class,
            NoSuchFieldException::class,
            InvocationTargetException::class,
            NoSuchMethodException::class
        )
        fun install(
            loader: ClassLoader, additionalClassPathEntries: List<File>,
            optimizedDirectory: File
        ) {
            val pathListField: Field = findField(loader, "pathList")
            val dexPathList: Any = pathListField.get(loader)
            ShareReflectUtil.expandFieldArray(
                dexPathList, "dexElements",
                makeDexElements(
                    dexPathList,
                    ArrayList<File>(additionalClassPathEntries), optimizedDirectory
                )
            )
        }

        @Throws(
            IllegalAccessException::class,
            InvocationTargetException::class,
            NoSuchMethodException::class
        )
        private fun makeDexElements(
            dexPathList: Any, files: ArrayList<File>, optimizedDirectory: File
        ): Array<Any> {
            val makeDexElements = findMethod(
                dexPathList, "makeDexElements",
                ArrayList::class.java,
                File::class.java
            )
            return makeDexElements.invoke(dexPathList, files, optimizedDirectory) as Array<Any>
        }
    }

}