package com.parksmart.adminbff;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * In-process stand-in for the downstream services (WireMock-style, without the dependency). Routes
 * are keyed by {@code "METHOD /path?query"}; unknown routes answer 404 with a problem body. Every
 * request is recorded with its headers and body.
 */
final class StubServices {

  record Reply(int status, String body, long delayMillis, Map<String, String> headers) {}

  record Received(
      String method, String pathAndQuery, Map<String, List<String>> headers, String body) {
    String header(String name) {
      return headers.entrySet().stream()
          .filter(e -> e.getKey().equalsIgnoreCase(name))
          .map(e -> e.getValue().get(0))
          .findFirst()
          .orElse(null);
    }
  }

  private final HttpServer server;
  private final Map<String, Reply> routes = new ConcurrentHashMap<>();
  final List<Received> received = new CopyOnWriteArrayList<>();

  StubServices() {
    try {
      server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.createContext("/", this::handle);
    server.start();
  }

  String baseUrl() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  void reset() {
    routes.clear();
    received.clear();
  }

  void on(String method, String pathAndQuery, int status, String body) {
    routes.put(method + " " + pathAndQuery, new Reply(status, body, 0, Map.of()));
  }

  void slow(String method, String pathAndQuery, long delayMillis, String body) {
    routes.put(method + " " + pathAndQuery, new Reply(200, body, delayMillis, Map.of()));
  }

  void redirect(String method, String pathAndQuery, String location) {
    routes.put(method + " " + pathAndQuery, new Reply(302, "", 0, Map.of("Location", location)));
  }

  List<Received> calls(String method, String pathPrefix) {
    return received.stream()
        .filter(r -> r.method().equals(method) && r.pathAndQuery().startsWith(pathPrefix))
        .toList();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String uri = exchange.getRequestURI().getRawPath();
    String query = exchange.getRequestURI().getRawQuery();
    String key = exchange.getRequestMethod() + " " + uri + (query == null ? "" : "?" + query);
    String requestBody =
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    received.add(
        new Received(
            exchange.getRequestMethod(),
            uri + (query == null ? "" : "?" + query),
            Map.copyOf(exchange.getRequestHeaders()),
            requestBody));
    Reply reply =
        routes.getOrDefault(
            key,
            new Reply(
                404,
                "{\"type\":\"https://parksmart.dev/problems/not-found\",\"title\":\"Resource not found\",\"status\":404,\"detail\":\"Not found\"}",
                0,
                Map.of()));
    if (reply.delayMillis() > 0) {
      try {
        Thread.sleep(reply.delayMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
    exchange
        .getResponseHeaders()
        .add(
            "Content-Type",
            reply.status() >= 400 ? "application/problem+json" : "application/json");
    reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
    try {
      exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
      if (bytes.length > 0) {
        exchange.getResponseBody().write(bytes);
      }
    } catch (IOException e) {
      // client gave up (timeout tests)
    } finally {
      exchange.close();
    }
  }
}
