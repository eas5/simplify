package org.cf.smalivm.opcode

import ExceptionFactory
import org.cf.smalivm.configuration.Configuration
import org.cf.smalivm.dex.CommonTypes
import org.cf.smalivm.dex.SmaliClassLoader
import org.cf.smalivm.type.ClassManager
import org.cf.smalivm.type.VirtualArray
import org.cf.smalivm.type.VirtualClass
import org.cf.smalivm2.ExecutionNode
import org.cf.smalivm2.Value
import org.cf.util.ClassNameUtils
import org.cf.util.Utils
import org.jf.dexlib2.builder.MethodLocation
import org.jf.dexlib2.iface.instruction.formats.Instruction23x
import org.slf4j.LoggerFactory
import java.lang.reflect.Array

class APutOp internal constructor(
    location: MethodLocation,
    child: MethodLocation,
    private val valueRegister: Int,
    private val arrayRegister: Int,
    private val indexRegister: Int,
    private val exceptionFactory: ExceptionFactory
) : Op(location, child) {

    init {
        exceptions.add(exceptionFactory.build(this, NullPointerException::class.java))
        exceptions.add(exceptionFactory.build(this, ArrayIndexOutOfBoundsException::class.java))
    }

    override fun execute(node: ExecutionNode) {
        val store = node.state.readRegister(valueRegister)
        var array = node.state.readRegister(arrayRegister)
        val index = node.state.readRegister(indexRegister)
        val throwsStoreException = throwsArrayStoreException(array, store, node.classManager)
        if (throwsStoreException) {
            val storeType = ClassNameUtils.internalToBinary(store.type)
            val exception = exceptionFactory.build(this, ArrayStoreException::class.java, storeType)
            node.addException(exception)
            node.clearChildren()
            return
        }
        if (array.isUnknown()) {
            // Do nothing.
        } else {
            if (store.isUnknown() || index.isUnknown()) {
                val type = array.type
                array = Value.unknown(type)
            } else {
                if (null == array.value) {
                    val exception = exceptionFactory.build(this, NullPointerException::class.java)
                    node.addException(exception)
                    node.clearChildren()
                    return
                }
                if (index.asInteger() >= Array.getLength(array.value)) {
                    val exception = exceptionFactory.build(this, ArrayIndexOutOfBoundsException::class.java)
                    node.addException(exception)
                    node.clearChildren()
                    return
                } else {
                    val set = castValue(name, store.value)
                    Array.set(array.value, index.asInteger(), set)
                    node.clearExceptions()
                }
            }
        }
        node.state.assignRegister(arrayRegister, array)
    }

    override fun getRegistersReadCount(): Int {
        return 3
    }

    override fun getRegistersAssignedCount(): Int {
        return 1
    }

    override fun toString(): String {
        return "$name r$valueRegister, r$arrayRegister, r$indexRegister"
    }

    companion object : OpFactory {
        private val log = LoggerFactory.getLogger(APutOp::class.java.simpleName)

        override fun build(
            location: MethodLocation,
            addressToLocation: Map<Int, MethodLocation>,
            classManager: ClassManager,
            exceptionFactory: ExceptionFactory,
            classLoader: SmaliClassLoader,
            configuration: Configuration
        ): Op {
            val child = Utils.getNextLocation(location, addressToLocation)
            val instr = location.instruction as Instruction23x
            val putRegister = instr.registerA
            val arrayRegister = instr.registerB
            val indexRegister = instr.registerC
            return APutOp(location, child, putRegister, arrayRegister, indexRegister, exceptionFactory)
        }

        private fun castValue(opName: String, value: Any?): Any? {
            if (value is Number) {
                return if (opName.endsWith("-boolean")) {
                    // Booleans are represented by integer literals; need to convert
                    val intValue = Utils.getIntegerValue(value)
                    intValue == 1
                } else {
                    val intValue = Utils.getIntegerValue(value)
                    if (opName.endsWith("-byte")) {
                        intValue.toByte()
                    } else if (opName.endsWith("-char")) {
                        // Characters, like boolean, are represented by integers
                        intValue.toInt().toChar()
                    } else if (opName.endsWith("-short")) {
                        intValue.toShort()
                    } else if (opName.endsWith("-object") && intValue == 0) {
                        // const/4 v0, 0x0 is null
                        null
                    } else {
                        log.warn("Unexpected aput-* postfix during numerical cast; returning null")
                        null
                    }
                }
            }
            return value
        }

        private fun isOverloadedPrimitiveType(type: String): Boolean {
            return ClassNameUtils.isPrimitive(type) &&
                    !(CommonTypes.FLOAT == type || CommonTypes.DOUBLE == type || CommonTypes.LONG == type)
        }

        private fun throwsArrayStoreException(arrayItem: Value, valueItem: Value, classManager: ClassManager): Boolean {
            val valueType = classManager.getVirtualType(valueItem.type)
            val arrayTypeType = classManager.getVirtualType(arrayItem.type)
            if (arrayTypeType is VirtualClass && arrayTypeType.getName() == CommonTypes.OBJECT) {
                val e = Exception("APutOp")
                log.warn("Storing value in Object[] and not enough type information to know if this may throw an exception.", e)
                return false
            }
            val arrayType = arrayTypeType as VirtualArray
            val arrayComponentType = arrayType.componentType
            if (valueType.instanceOf(arrayComponentType)) {
                return false
            }

            // Types: Z B C S I are all represented identically in bytecode (e.g. const/4)
            val valueTypeName = valueType.name
            val arrayComponentTypeName = arrayComponentType.name
            if (isOverloadedPrimitiveType(valueTypeName) && isOverloadedPrimitiveType(arrayComponentTypeName)) {
                // Trying to store something that looks like one type into another type, but they're compatible.
                // For example, a method returns Z, which is actually either 0x1 or 0x0 and is then storing it in an int
                // array.
                // TODO: figure out what dalvik actually does when you try to aput 0x2 into [B
                // also try to find other edge cases, like Integer.MAX_VALUE in [S
                return false
            }

            // Trying to store a known value of 0 and a primitive type of integer into an array of object reference.
            // This is how Dalvik does null.
            // TODO: see what happens when Dalvik makes a Z and uses as null, might want to consider other types
            val storingNull = !valueItem.isUnknown()
                    && valueTypeName == CommonTypes.INTEGER
                    && valueItem.asInteger() == 0
                    && !ClassNameUtils.isPrimitive(arrayComponentTypeName)
            return !storingNull
        }
    }
}