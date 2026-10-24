package kotlin.test

@Target(AnnotationTarget.FUNCTION)
annotation class Test

class AssertionFailed(message: String) : AssertionError(message)

fun <T> assertEquals(expected: T, actual: T, message: String? = null) {
    if (expected != actual) throw AssertionFailed("${message ?: "assertEquals"}: expected <$expected> but was <$actual>")
}
fun assertTrue(actual: Boolean, message: String? = null) { if (!actual) throw AssertionFailed(message ?: "expected true") }
fun assertFalse(actual: Boolean, message: String? = null) { if (actual) throw AssertionFailed(message ?: "expected false") }
fun assertNull(actual: Any?, message: String? = null) { if (actual != null) throw AssertionFailed(message ?: "expected null but was <$actual>") }
fun <T : Any> assertNotNull(actual: T?, message: String? = null): T = actual ?: throw AssertionFailed(message ?: "expected non-null")
inline fun <reified T> assertIs(value: Any?, message: String? = null): T {
    if (value !is T) throw AssertionFailed(message ?: "expected ${T::class.simpleName} but was <$value>")
    return value
}
inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
    try { block() } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionFailed("expected ${T::class.simpleName} but got $e")
    }
    throw AssertionFailed("expected ${T::class.simpleName} but nothing was thrown")
}
