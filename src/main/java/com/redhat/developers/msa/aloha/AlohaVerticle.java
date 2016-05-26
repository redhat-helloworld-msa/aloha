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
import java.util.List;

import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import com.github.kennedyoliveira.hystrix.contrib.vertx.metricsstream.EventMetricsStreamHandler;
import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.EmptySpanCollectorMetricsHandler;
import com.github.kristofa.brave.ServerSpan;
import com.github.kristofa.brave.http.DefaultSpanNameProvider;
import com.github.kristofa.brave.http.HttpSpanCollector;
import com.github.kristofa.brave.http.StringServiceNameProvider;
import com.github.kristofa.brave.httpclient.BraveHttpRequestInterceptor;
import com.github.kristofa.brave.httpclient.BraveHttpResponseInterceptor;

import feign.Logger;
import feign.Logger.Level;
import feign.httpclient.ApacheHttpClient;
import feign.hystrix.HystrixFeign;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;
import io.vertx.ext.web.handler.StaticHandler;

public class AlohaVerticle extends AbstractVerticle {

    @Override
    public void start() throws Exception {
        Router router = Router.router(vertx);
        router.route().handler(BodyHandler.create());
        router.route().handler(CorsHandler.create("*")
            .allowedMethod(HttpMethod.GET)
            .allowedHeader("Content-Type"));

        // Aloha EndPoint
        router.get("/api/aloha").handler(ctx -> {
            ctx.response().end(aloha());
        });

        // Aloha Chained Endpoint
        router.get("/api/aloha-chaining").handler(ctx -> {
            alohaChaining(list -> {
                ctx.response()
                    .putHeader("Content-Type", "application/json")
                    .end(Json.encode(list));
            });
        });

        // Health Check
        router.get("/api/health").handler(ctx -> {
            ctx.response().end("I'm ok");
        });

        // Hysrix Stream Endpoint
        router.get(EventMetricsStreamHandler.DEFAULT_HYSTRIX_PREFIX)
            .handler(EventMetricsStreamHandler.createHandler());

        // Static content
        router.route("/*").handler(StaticHandler.create());


        vertx.createHttpServer().requestHandler(router::accept).listen(8080);
        System.out.println("Service running at 0.0.0.0:8080");
    }

    private String aloha() {
        String hostname = System.getenv().getOrDefault("HOSTNAME", "unknown");
        return String.format("Aloha mai %s", hostname);
    }

    private void alohaChaining(Handler<List<String>> resultHandler) {
        vertx.<String> executeBlocking(
            // Invoke the service in a worker thread, as it's blocking.
            future -> future.complete(getNextService().bonjour()),
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
    private BonjourService getNextService() {
        final Brave brave = new Brave.Builder("aloha")
            .spanCollector(HttpSpanCollector.create("http://zipkin-query:9411", new EmptySpanCollectorMetricsHandler()))
            .build();
        final String serviceName = "bonjour";
        // This stores the Original/Parent ServerSpan from ZiPkin.
        final ServerSpan serverSpan = brave.serverSpanThreadBinder().getCurrentServerSpan();
        final CloseableHttpClient httpclient =
            HttpClients.custom()
                .addInterceptorFirst(new BraveHttpRequestInterceptor(brave.clientRequestInterceptor(), new StringServiceNameProvider(serviceName), new DefaultSpanNameProvider()))
                .addInterceptorFirst(new BraveHttpResponseInterceptor(brave.clientResponseInterceptor()))
                .build();
        final String url = String.format("http://%s:8080/", serviceName);
        return HystrixFeign.builder()
            // Use apache HttpClient which contains the ZipKin Interceptors
            .client(new ApacheHttpClient(httpclient))
            // Bind Zipkin Server Span to Feign Thread
            .requestInterceptor((t) -> brave.serverSpanThreadBinder().setCurrentSpan(serverSpan))
            .logger(new Logger.ErrorLogger()).logLevel(Level.BASIC)
            .target(BonjourService.class, url,
                () -> "Bonjour response (fallback)");
    }

}
