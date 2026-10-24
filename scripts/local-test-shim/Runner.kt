import java.lang.reflect.InvocationTargetException

fun main(args: Array<String>) {
    var passed = 0
    val failures = mutableListOf<String>()
    for (cn in args) {
        val cls = Class.forName(cn)
        for (m in cls.declaredMethods.filter { it.isAnnotationPresent(kotlin.test.Test::class.java) }.sortedBy { it.name }) {
            val inst = cls.getDeclaredConstructor().newInstance()
            try { m.invoke(inst); passed++ }
            catch (e: InvocationTargetException) { failures += "${cls.simpleName}.${m.name}: ${e.targetException.message ?: e.targetException}" }
        }
    }
    println("passed=$passed failed=${failures.size}")
    failures.forEach { println("  FAIL $it") }
    if (failures.isNotEmpty()) System.exit(1)
}
