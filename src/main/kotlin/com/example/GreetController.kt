package com.example

import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.QueryValue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Exposes the bug over HTTP. Each endpoint installs a `RequestIdElement("request-42")` and then
 * calls the matching `@Validated` [GreetService] method.
 *
 *  - `GET /greet/suspend` -> `requestId` survives -> `...before=request-42 afterThreadHop=request-42`
 *  - `GET /greet/flow`    -> `requestId` is lost   -> `...before=null afterThreadHop=null`
 */
@Controller("/greet")
open class GreetController(private val greetService: GreetService) {

    @Get("/flow")
    suspend fun flow(@QueryValue(defaultValue = "world") name: String): List<String> =
        withContext(RequestIdElement("request-42")) {
            greetService.greetFlow(GreetRequest(name)).toList()
        }

    @Get("/suspend")
    suspend fun suspend(@QueryValue(defaultValue = "world") name: String): String =
        withContext(RequestIdElement("request-42")) {
            greetService.greetSuspend(GreetRequest(name))
        }
}
