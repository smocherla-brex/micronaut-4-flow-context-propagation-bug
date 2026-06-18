package com.example

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.micronaut.http.client.HttpClient
import io.micronaut.http.client.annotation.Client
import io.micronaut.test.extensions.kotest5.annotation.MicronautTest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Reproduces the Micronaut 4 regression: a non-`suspend`, `Flow`-returning method loses the
 * coroutine/request context across a thread hop, while the equivalent `suspend` method preserves it.
 *
 * It reproduces at both conversion sites:
 *  - the AOP boundary — a `@Validated` [GreetService] (context = a kotlinx [RequestIdElement]); and
 *  - the controller response serialization — a plain [StreamController] with no `@Validated`
 *    (context = Micronaut's own `PropagatedContext`), i.e. micronaut-core#11659.
 *
 * The `suspend ...` tests PASS; the `FLOW ...` tests FAIL on Micronaut 4 (observed `afterThreadHop=null`),
 * which is the bug. See README.md for the root cause.
 */
@MicronautTest
class FlowContextPropagationTest(
    private val greetService: GreetService,
    @param:Client("/") private val httpClient: HttpClient,
) : StringSpec({

    val expected = "Hello world! requestId before=request-42 afterThreadHop=request-42"

    // ---- Service level: the @Validated AOP boundary ----

    "suspend service method PRESERVES the request context across a thread hop" {
        val result = withContext(RequestIdElement("request-42")) {
            greetService.greetSuspend(GreetRequest("world"))
        }
        result shouldBe expected
    }

    "FLOW service method LOSES the request context across a thread hop (the bug)" {
        val result = withContext(RequestIdElement("request-42")) {
            greetService.greetFlow(GreetRequest("world")).toList().first()
        }
        result shouldBe expected
    }

    // ---- Controller level: the same service reached over HTTP ----

    "suspend controller endpoint PRESERVES the request context" {
        httpClient.toBlocking().retrieve("/greet/suspend?name=world") shouldBe expected
    }

    "FLOW controller endpoint LOSES the request context (the bug)" {
        // The /flow endpoint returns a JSON array with a single element.
        httpClient.toBlocking().retrieve("/greet/flow?name=world") shouldBe "[\"$expected\"]"
    }
})
