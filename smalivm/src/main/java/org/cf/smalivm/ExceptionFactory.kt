package org.cf.smalivm

import org.cf.smalivm.dex.SmaliClassLoader
import org.cf.util.ClassNameUtils

class ExceptionFactory internal constructor(private val classLoader: SmaliClassLoader) {
    @JvmOverloads
    fun build(exceptionClass: Class<out Throwable>, message: String? = null): Throwable {
        try {
            val ctor = exceptionClass.getDeclaredConstructor(String::class.java)
            ctor.isAccessible = true
            return ctor.newInstance(message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Exception()
    }

    @JvmOverloads
    fun build(className: String, message: String? = null): Throwable {
        val binaryName = ClassNameUtils.internalToBinary(className)
        try {
            val exceptionClass = classLoader.loadClass(binaryName) as Class<Throwable>
            return build(exceptionClass, message)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Exception()
    }
}
