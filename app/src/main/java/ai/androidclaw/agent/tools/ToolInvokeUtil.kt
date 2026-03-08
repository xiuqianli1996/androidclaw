package ai.androidclaw.agent.tools

import java.lang.reflect.Method

object ToolInvokeUtil {

    fun invoke(method: Method, target: Any, parameters: Map<String, Any>): Any? {
        val values = parameters.values.toList()
        val paramTypes = method.parameterTypes
        val args = Array(paramTypes.size) { index ->
            val raw = values.getOrNull(index)
            convert(raw, paramTypes[index])
        }
        return method.invoke(target, *args)
    }

    private fun convert(value: Any?, targetType: Class<*>): Any? {
        if (value == null) {
            return when (targetType) {
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Boolean.TYPE -> false
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
        }

        if (targetType.isInstance(value)) return value

        val text = value.toString()
        return when (targetType) {
            java.lang.String::class.java -> text
            java.lang.Integer.TYPE, java.lang.Integer::class.java -> text.toIntOrNull() ?: 0
            java.lang.Long.TYPE, java.lang.Long::class.java -> text.toLongOrNull() ?: 0L
            java.lang.Float.TYPE, java.lang.Float::class.java -> text.toFloatOrNull() ?: 0f
            java.lang.Double.TYPE, java.lang.Double::class.java -> text.toDoubleOrNull() ?: 0.0
            java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> text.equals("true", ignoreCase = true)
            java.util.Map::class.java -> value as? Map<*, *> ?: emptyMap<String, Any>()
            else -> value
        }
    }
}
