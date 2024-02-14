package com.example.myapplication.hotfix

import java.io.File
import java.io.IOException
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Arrays

object ShareReflectUtil {
    /**
    * @description 从instance 到其父类 找 name 属性
    * @param
    * @return
    * @author wgt; @time 2024/2/11 下午2:20
    */
    fun findField(instance: Any, name: String): Field {
        var clazz = instance.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(name)
                if(!field.isAccessible) {
                    field.isAccessible = true
                }
                return field
            } catch (e: NoSuchFieldException) {
                // ignore and search next
            }
            clazz = clazz.superclass
        }
        throw NoSuchFieldException("Field $name not found in ${instance.javaClass}")
    }

    /**
     * 从 instance 到其父类 找 name 属性
     */
    fun findMethod(instance: Any, name: String, vararg parameterTypes: Class<*>): Method {
        var clazz = instance.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(name, *parameterTypes)
                if (!method.isAccessible) {
                    method.isAccessible = true
                }
                return method
            } catch (_: NoSuchMethodException) {
            }
            clazz = clazz.superclass
        }
        // 如果从子类和父类中都没有找到则抛出异常
        throw NoSuchMethodException(
            "Method "
                    + name
                    + " with parameters "
                    + Arrays.asList(*parameterTypes)
                    + " not found in " + instance.javaClass
        )
    }

    fun expandFiledArray(obj: Any, fieldName: String, patchedElements: List<Any>) {

    }

    fun makeDexElements(
        dexPathList: Any,
        files: List<File>,
        optimizedDirectory: File,
        suppressedExceptions: List<IOException>
    ): Array<Object> {
        // Android 6\7\8\9源码，都存在makePathElement方法
        val makePathElements = findMethod(dexPathList, "makeDexPathElements")
        return makePathElements.invoke(
            dexPathList,
            files,
            optimizedDirectory,
            suppressedExceptions
        ) as Array<Object>
    }

    /**
     * @param instance
     * @param fieldName
     * @param patchElements 补丁的Element数组
     * @throws NoSuchFieldException
     * @throws IllegalArgumentException
     * @throws IllegalAccessException
     */
    @Throws(
        NoSuchFieldException::class,
        IllegalArgumentException::class,
        IllegalAccessException::class
    )
    fun expandFieldArray(instance: Any, fieldName: String, patchElements: Array<*>) {
        //拿到 classloader中的dexElements 数组
        val dexElementsField = findField(instance, fieldName)
        //old Element[]
        val dexElements = dexElementsField[instance] as Array<*>


        //合并后的数组
        val newElements = java.lang.reflect.Array.newInstance(
            dexElements.javaClass.componentType,
            dexElements.size + patchElements.size
        ) as Array<*>

        // 先拷贝新数组，待修复包放在前面
        System.arraycopy(patchElements, 0, newElements, 0, patchElements.size)
        System.arraycopy(dexElements, 0, newElements, patchElements.size, dexElements.size)

        //修改 classLoader中 pathList的 dexElements
        dexElementsField[instance] = newElements
    }
}