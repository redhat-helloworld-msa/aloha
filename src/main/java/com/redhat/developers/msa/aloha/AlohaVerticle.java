/**
 * JBoss, Home of Professional Open Source
 * Copyright 2016, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.redhat.developers.msa.aloha;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import com.github.kennedyoliveira.hystrix.contrib.vertx.metricsstream.EventMetricsStreamHandler;
import com.redhat.developers.msa.aloha.tracing.AlohaHttpRequestInterceptor;
import com.redhat.developers.msa.aloha.tracing.AlohaHttpResponseInterceptor;
import com.redhat.developers.msa.aloha.tracing.HttpHeadersExtractAdapter;
import com.redhat.developers.msa.aloha.tracing.TracerResolver;

import feign.Logger;
import feign.Logger.Level;
import feign.httpclient.ApacheHttpClient;
import feign.hystrix.HystrixFeign;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.Tracer;
import io.opentracing.propagation.Format;
import io.opentracing.tag.Tags;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.auth.jwt.JWTAuth;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.JWTAuthHandler;
import io.vertx.ext.web.handler.StaticHandler;

public class AlohaVerticle extends AbstractVerticle {
    private static final String TRACING_REQUEST_SPAN = "tracing.requestSpan";
    private final Tracer tracer = TracerResolver.getTracer();

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        vertx.deployVerticle(new AlohaVerticle());
    }

    public AlohaVerticle() {
    }

    @Override
    public void start() throws Exception {
        Router router = Router.router(vertx);
        router.route().handler(ctx -> {
            // first, we build or rebuild a context based on the request data
            // there are two possible scenarios here: the user is calling this endpoint directly,
            // or this endpoint is being called by another service, like /api/hola-chaining .
            // If we are being called directly, this "extract" will not find any trace state and will create a new context
            // from scratch.
            // If we are being called by Hola, then we should have some HTTP headers with the trace state (trace ID), on which case
            // we create a span context with that information.
            SpanContext spanContext = tracer.extract(Format.Builtin.HTTP_HEADERS, new HttpHeadersExtractAdapter(ctx.request().headers()));

            // now that we have the span context, we create a "child" span for this request
            Span requestSpan = tracer.buildSpan(ctx.request().method().name())
                    .asChildOf(spanContext)
                    .start();

            Tags.HTTP_URL.set(requestSpan, ctx.request().absoluteURI());
            // we store the request span within the request data, for consumption on children spans that we might need
            ctx.data().put(TRACING_REQUEST_SPAN, requestSpan);

            // continue with the request processing
            ctx.next();

            // before the request finishes, we want to mark the request span as finished as well
            ctx.addBodyEndHandler(v -> {
                Tags.HTTP_STATUS.set(requestSpan, ctx.response().getStatusCode());
                requestSpan.finish();
            });
        });

        router.route().handler(BodyHandler.create());
        router.route().handler(CorsHandler.create("*")
            .allowedMethods(new HashSet<>(Arrays.asList(HttpMethod.values())))
            .allowedHeader("Origin, X-Requested-With, Content-Type, Accept, Authorization"));

        // Aloha EndPoint
        router.get("/api/aloha").handler(ctx -> ctx.response().end(aloha()));

        String keycloackServer = System.getenv("KEYCLOAK_AUTH_SERVER_URL");

        if (keycloackServer != null) {
            // Create a JWT Auth Provider
            JWTAuth jwt = JWTAuth.create(vertx, new JsonObject()
                .put("public-key",
                    "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEArfmb1i36YGxYxusjzpNxmw9a/+M40naa5RxtK826nitmWESF9XiXm6bHLWmRQyhAZluFK4RZDLhQJFZTLpC/w8HdSDETYGqnrP04jL3/pV0Mw1ReKSpzi3tIde+04xGuiQM6nuR84iRraLxtoNyIiqFmHy5pmI9hQhctfZNOVvggntnhXdt/VKuguBXqitFwGbfEgrJTeRvnTkK+rR5MsRDHA3iu2ZYaM4YNAoDbqGyoI4Jdv5Kl1LsP3qESYNeagRz6pIfDZWOoJ58p/TldVt2h70S1bzappbgs8ZbmJXg+pHWcKvNutp5y8nYw30qzU73pX6DW9JS936OB6PiU0QIDAQAB"));
            router.route("/api/aloha-secured").handler(JWTAuthHandler.create(jwt));
        }

        router.get("/api/aloha-secured").handler(ctx -> {
            User user = ctx.user();
            ctx.response().end("This is a secured resource. You're logged as " + user.principal().getString("name"));
        });

        // Aloha Chained Endpoint
        router.get("/api/aloha-chaining").handler(ctx -> alohaChaining(ctx, (list) -> ctx.response()
            .putHeader("Content-Type", "application/json")
            .end(Json.encode(list))));

        // Health Check
        router.get("/api/health").handler(ctx -> ctx.response().end("I'm ok"));

        // Hystrix Stream Endpoint
        router.get(EventMetricsStreamHandler.DEFAULT_HYSTRIX_PREFIX).handler(EventMetricsStreamHandler.createHandler());

        // Static content
        router.route("/*").handler(StaticHandler.create());

        vertx.createHttpServer().requestHandler(router::accept).listen(8080);
        System.out.println("Service running at 0.0.0.0:8080");
    }

    private String aloha() {
        String hostname = System.getenv().getOrDefault("HOSTNAME", "unknown");
        return String.format("Aloha mai %s", hostname);
    }

    private void alohaChaining(RoutingContext context, Handler<List<String>> resultHandler) {
        vertx.<String> executeBlocking(
            // Invoke the service in a worker thread, as it's blocking.
            future -> future.complete(getNextService(context).bonjour()),
            ar -> {
                // Back to the event loop
                // result cannot be null, hystrix would have called the fallback.
                String result = ar.result();
                List<String> greetings = new ArrayList<>();
                greetings.add(aloha());
                greetings.add(result);
                resultHandler.handle(greetings);
            });
    }

    /**
     * This is were the "magic" happens: it creates a Feign, which is a proxy interface for remote calling a REST endpoint with
     * Hystrix fallback support.
     *
     * @return The feign pointing to the service URL and with Hystrix fallback.
     */
    private BonjourService getNextService(RoutingContext context) {
        String url = System.getenv("BONJOUR_SERVER_URL");
        if (null == url || url.isEmpty()) {
            url = String.format("http://%s:%s", System.getenv("BONJOUR_SERVICE_HOST"), System.getenv("BONJOUR_SERVICE_PORT"));
        }

        // as we are calling a service somewhere else, it's a good idea to instrument the request
        // with the trace information, so that we can get a distributed tracing information!
        // we stored the spanContext for this request on the context.data, so, we retrieve it first
        Span parentSpan = (Span) context.data().get(TRACING_REQUEST_SPAN);
        Span span = tracer.buildSpan("GET").asChildOf(parentSpan).start();

        // note that we add our two interceptors to the http client, so that we can add our trace state
        // and mark the trace as finished once we get the answer
        final CloseableHttpClient httpclient = HttpClients.custom()
                .addInterceptorFirst(new AlohaHttpRequestInterceptor(span))
                .addInterceptorFirst(new AlohaHttpResponseInterceptor(span))
                .build();

        return HystrixFeign.builder()
                .logger(new Logger.ErrorLogger()).logLevel(Level.BASIC)
                .client(new ApacheHttpClient(httpclient))
                .target(BonjourService.class, url, () -> "Bonjour response (fallback)");
    }
}
