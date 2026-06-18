package com.example

import io.micronaut.core.annotation.Introspected
import io.micronaut.validation.Validated
import jakarta.inject.Singleton
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

@Introspected
data class GreetRequest(@field:NotBlank val name: String)

/**
 * A `@Validated` Micronaut bean. Because of `@Validated` + `@Valid`, Micronaut wraps it in an AOP
 * proxy, and the way the proxy handles the method's *return type* differs between a `Flow` and a
 * `suspend` function — which is the whole bug.
 *
 * Both methods do the same thing: read [RequestId], hop threads (`withContext(Dispatchers.IO)`,
 * standing in for any reactive/DB resume), and read [RequestId] again.
 */
@Singleton
@Validated
open class GreetService {

    /**
     * `Flow`-returning. On Micronaut 4 the AOP interceptor classifies this as a reactive
     * `PUBLISHER` and round-trips it `Flow -> Publisher -> Flow` (via micronaut-kotlin's
     * `FlowPublisherConverterRegistrar`, whose `asPublisher()` carries no `CoroutineContext`). The
     * `RequestIdElement` is therefore dropped, so `requestId` reads `null` after (and even before)
     * the hop.
     */
    open fun greetFlow(@Valid request: GreetRequest): Flow<String> = flow {
        val before = RequestId.get()
        withContext(Dispatchers.IO) { /* thread hop */ }
        val after = RequestId.get()
        emit("Hello ${request.name}! requestId before=$before afterThreadHop=$after")
    }

    /**
     * `suspend`-returning. Classified as `COMPLETION_STAGE` (the Kotlin coroutine adapter), which
     * preserves the coroutine context — so `RequestId` survives the hop.
     */
    open suspend fun greetSuspend(@Valid request: GreetRequest): String {
        val before = RequestId.get()
        withContext(Dispatchers.IO) { /* thread hop */ }
        val after = RequestId.get()
        return "Hello ${request.name}! requestId before=$before afterThreadHop=$after"
    }
}
