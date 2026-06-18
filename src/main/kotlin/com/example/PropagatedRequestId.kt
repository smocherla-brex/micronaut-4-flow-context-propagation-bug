package com.example

import io.micronaut.core.propagation.MutablePropagatedContext
import io.micronaut.core.propagation.PropagatedContext
import io.micronaut.core.propagation.PropagatedContextElement
import io.micronaut.http.annotation.RequestFilter
import io.micronaut.http.annotation.ServerFilter

/**
 * Micronaut's OWN request-scoped context element — the exact kind micronaut-core#11659 is about.
 * Unlike [RequestIdElement] (a kotlinx `ThreadContextElement` we install by hand), this is framework
 * machinery: Micronaut seeds it per-request and is responsible for propagating it.
 */
class RequestIdContextElement(val requestId: String) : PropagatedContextElement

/** Reads the request id from Micronaut's [PropagatedContext] (thread-local backed). */
fun propagatedRequestId(): String? =
    PropagatedContext.getOrEmpty()
        .find(RequestIdContextElement::class.java)
        .map { it.requestId }
        .orElse(null)

/** Installs a [RequestIdContextElement] into the propagated context for every request. */
@ServerFilter("/**")
class RequestIdPropagatingFilter {
    @RequestFilter
    fun onRequest(mutablePropagatedContext: MutablePropagatedContext) {
        mutablePropagatedContext.add(RequestIdContextElement("request-42"))
    }
}
