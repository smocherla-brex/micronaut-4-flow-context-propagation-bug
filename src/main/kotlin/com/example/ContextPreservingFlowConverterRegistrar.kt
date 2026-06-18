package com.example

import io.micronaut.context.annotation.Replaces
import io.micronaut.core.convert.MutableConversionService
import io.micronaut.core.convert.TypeConverterRegistrar
import io.micronaut.kotlin.converters.FlowPublisherConverterRegistrar
import jakarta.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.reactive.asPublisher
import org.reactivestreams.Publisher

/**
 * A workaround (NOT a general fix) — kept here only to prove the conversion is the culprit.
 *
 * Replaces Micronaut's stock [FlowPublisherConverterRegistrar] — whose `Flow -> Publisher`
 * conversion uses the no-context `asPublisher()` and drops the coroutine context — with one that
 * snapshots context-bearing [kotlinx.coroutines.ThreadContextElement]s on the caller's thread and
 * passes them into `asPublisher(context)`, so the flow is collected with the context restored.
 *
 * ## Why this is NOT a general fix
 * The converter is a plain (non-`suspend`) `TypeConverter`, so it cannot call
 * `currentCoroutineContext()` to obtain the *actual* live context. It can only rebuild a context
 * from elements it explicitly knows how to read back from their thread-locals — here just
 * [RequestId]. There is **no way to capture an arbitrary `ThreadContextElement` generically**:
 * kotlinx exposes no registry of active elements, and each element's state lives in its own
 * type-specific thread-local (`MDCContext` -> SLF4J `MDC`, OpenTelemetry -> `Context.current()`,
 * an app's security/identity -> its own holder, …). So any converter-level fix degenerates into a
 * hard-coded list of "the elements this app happens to care about" and silently drops everything
 * else — which is why the real fix belongs upstream (see README): don't round-trip a `Flow` through
 * `Publisher` at all (the Micronaut 3 behavior), or integrate the conversion with Reactor automatic
 * context propagation so every registered `ThreadLocalAccessor` is restored.
 *
 * With this bean enabled, all four tests in FlowContextPropagationTest pass (the single element this
 * app cares about, [RequestId], survives). Confirmed: uncommenting the two annotations below turns
 * `./gradlew test` from 2 failures to all-green.
 *
 * Left commented out so the default `./gradlew test` still demonstrates the bug. Enable it by
 * uncommenting the @Singleton + @Replaces annotations below.
 */
// @Singleton
// @Replaces(FlowPublisherConverterRegistrar::class)
class ContextPreservingFlowConverterRegistrar : TypeConverterRegistrar {

    override fun register(conversionService: MutableConversionService) {
        conversionService.addConverter(Flow::class.java, Publisher::class.java) { flow ->
            // Snapshot ONLY the elements we know how to read back (here: RequestId). This is the
            // crux of why a converter-level fix can't be generic — every other ThreadContextElement
            // (MDC, OTel, security, …) would have to be added by hand and is otherwise lost.
            @Suppress("UNCHECKED_CAST")
            (flow as Flow<Any>).asPublisher(RequestIdElement(RequestId.get()))
        }
        conversionService.addConverter(Publisher::class.java, Flow::class.java) { publisher ->
            publisher.asFlow()
        }
    }
}
