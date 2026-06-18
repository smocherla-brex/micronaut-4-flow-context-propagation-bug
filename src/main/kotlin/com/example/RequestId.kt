package com.example

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A request-scoped value backed by a [ThreadLocal]. Stands in for the kind of context apps rely on
 * surviving across coroutine thread hops — e.g. SLF4J MDC, OpenTelemetry trace context, or a
 * security/identity context.
 */
object RequestId {
    private val threadLocal = ThreadLocal<String?>()
    fun get(): String? = threadLocal.get()
    fun set(value: String?) {
        if (value == null) threadLocal.remove() else threadLocal.set(value)
    }
}

/**
 * Propagates [RequestId] across coroutine dispatches via a kotlinx [ThreadContextElement] — exactly
 * the mechanism `kotlinx.coroutines.slf4j.MDCContext` uses for MDC. On every resume the element
 * re-installs the captured value on the current thread.
 */
class RequestIdElement(private val value: String?) : ThreadContextElement<String?> {

    companion object Key : CoroutineContext.Key<RequestIdElement>

    override val key: CoroutineContext.Key<RequestIdElement> get() = Key

    override fun updateThreadContext(context: CoroutineContext): String? {
        val previous = RequestId.get()
        RequestId.set(value)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
        RequestId.set(oldState)
    }
}
