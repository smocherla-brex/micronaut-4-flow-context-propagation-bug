package com.example

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * A plain `@Controller` — NO `@Validated`, no custom AOP advice. This exercises the *second*
 * conversion site: when an endpoint returns a `Flow`, Micronaut converts it `Flow -> Publisher` to
 * serialize the HTTP response, through the same context-less `FlowPublisherConverterRegistrar`.
 *
 * The request id lives in Micronaut's own [PropagatedContext] (seeded by [RequestIdPropagatingFilter]),
 * so this is the framework losing *its own* propagated context across a thread hop — exactly
 * micronaut-core#11659. The `suspend` endpoint keeps it; the non-`suspend` `Flow` endpoint loses it.
 */
@Controller("/stream")
class StreamController {

    /** Non-`suspend`, returns `Flow` directly -> response serialization converts `Flow -> Publisher`. */
    @Get("/flow")
    fun flow(): Flow<String> = flow {
        val before = propagatedRequestId()
        withContext(Dispatchers.IO) { /* thread hop */ }
        val after = propagatedRequestId()
        emit("requestId before=$before afterThreadHop=$after")
    }

    @Get("/suspend")
    suspend fun suspend(): String {
        val before = propagatedRequestId()
        withContext(Dispatchers.IO) { /* thread hop */ }
        val after = propagatedRequestId()
        return "requestId before=$before afterThreadHop=$after"
    }
}
