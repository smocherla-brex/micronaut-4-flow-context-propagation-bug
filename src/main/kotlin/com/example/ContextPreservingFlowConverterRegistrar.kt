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
 * THE FIX (a workaround application-side; the real fix belongs upstream in micronaut-kotlin).
 *
 * Replaces Micronaut's stock [FlowPublisherConverterRegistrar] — whose `Flow -> Publisher`
 * conversion uses the no-context `asPublisher()` and drops the coroutine context — with one that
 * **captures the context-bearing [kotlinx.coroutines.ThreadContextElement]s at conversion time** and
 * passes them into `asPublisher(context)`, so the flow is collected with the context restored.
 *
 * The converter is a plain (non-`suspend`) `TypeConverter`, so it cannot call
 * `currentCoroutineContext()`; instead it snapshots the elements from their thread-locals on the
 * caller's thread (where they are still set) — here just [RequestId]. A real app would capture all
 * the elements it cares about (MDC via `MDCContext()`, OpenTelemetry via `Context.current()
 * .asContextElement()`, etc.).
 *
 * With this bean enabled, all four tests in FlowContextPropagationTest pass — the `Flow` path then
 * preserves the request context across the thread hop, just like the `suspend` path. (Confirmed:
 * uncommenting the two annotations below turns `./gradlew test` from 2 failures to all-green.)
 *
 * Left commented out so the default `./gradlew test` still demonstrates the bug. Enable it by
 * uncommenting the @Singleton + @Replaces annotations below.
 */
// @Singleton
// @Replaces(FlowPublisherConverterRegistrar::class)
class ContextPreservingFlowConverterRegistrar : TypeConverterRegistrar {

    override fun register(conversionService: MutableConversionService) {
        conversionService.addConverter(Flow::class.java, Publisher::class.java) { flow ->
            // Capture the request context now (still on the caller's thread) and carry it into the
            // flow's collection so it survives every subsequent thread resume.
            @Suppress("UNCHECKED_CAST")
            (flow as Flow<Any>).asPublisher(RequestIdElement(RequestId.get()))
        }
        conversionService.addConverter(Publisher::class.java, Flow::class.java) { publisher ->
            publisher.asFlow()
        }
    }
}
