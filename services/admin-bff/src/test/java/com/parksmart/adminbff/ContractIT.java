package com.parksmart.adminbff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Contract tests against docs/openapi/admin-bff.yaml: the implemented routes are exactly the
 * documented operations, and real responses conform to the documented schemas (required fields
 * present, no undocumented fields, types, enums, patterns, nullability).
 */
class ContractIT extends IntegrationTest {

  static final OpenAPI API = parse();

  @Autowired
  @Qualifier("requestMappingHandlerMapping")
  RequestMappingHandlerMapping mappings;

  static OpenAPI parse() {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    options.setResolveFully(true);
    var result =
        new OpenAPIV3Parser()
            .readLocation(
                Path.of("../../docs/openapi/admin-bff.yaml")
                    .toAbsolutePath()
                    .normalize()
                    .toUri()
                    .toString(),
                null,
                options);
    assertThat(result.getMessages()).isEmpty();
    return result.getOpenAPI();
  }

  @Test
  void implemented_routes_are_exactly_the_documented_operations() {
    Set<String> documented = new TreeSet<>();
    API.getPaths()
        .forEach((path, item) ->
            item.readOperationsMap()
                .keySet()
                .forEach(method -> documented.add(method + " " + normalize(path))));

    Set<String> implemented = new TreeSet<>();
    mappings
        .getHandlerMethods()
        .forEach((info, handler) -> {
          if (!handler.getBeanType().getPackageName().startsWith("com.parksmart.adminbff.api")) {
            return;
          }
          for (String path : info.getPatternValues()) {
            info.getMethodsCondition()
                .getMethods()
                .forEach(m -> implemented.add(m.name() + " " + normalize(path)));
          }
        });

    assertThat(implemented).isEqualTo(documented);
  }

  @Test
  void moderation_queue_conforms() throws Exception {
    UUID host = UUID.randomUUID();
    UUID ok = UUID.randomUUID();
    UUID broken = UUID.randomUUID();
    STUB.on(
        "GET",
        "/admin/v1/listings?status=PENDING_REVIEW&limit=20",
        200,
        Fixtures.page("c1", Fixtures.summary(ok, "Spot"), Fixtures.summary(broken, "Broken")));
    STUB.on("GET", "/api/v1/listings/" + ok, 200, Fixtures.listing(ok, host, "PENDING_REVIEW"));
    STUB.on("GET", "/api/v1/listings/" + broken, 500, "");
    STUB.on("GET", "/api/v1/profiles/" + host, 200, Fixtures.profile(host, "Asha"));

    assertConforms(
        "GET",
        "/admin/v1/moderation/listings",
        "200",
        get("/admin/v1/moderation/listings", admin()).andExpect(status().isOk()));
  }

  @Test
  void moderation_outcomes_conform() throws Exception {
    UUID listing = UUID.randomUUID();
    UUID review = UUID.randomUUID();
    STUB.on(
        "POST",
        "/admin/v1/listings/" + listing + "/approve",
        200,
        Fixtures.listing(listing, UUID.randomUUID(), "PUBLISHED"));
    STUB.on(
        "POST",
        "/admin/v1/listings/" + listing + "/reject",
        200,
        Fixtures.listing(listing, UUID.randomUUID(), "REJECTED"));
    STUB.on("POST", "/admin/v1/reviews/" + review + "/hide", 200, Fixtures.review(review));

    assertConforms(
        "POST",
        "/admin/v1/moderation/listings/{listingId}/approve",
        "200",
        postJson("/admin/v1/moderation/listings/" + listing + "/approve", "{}", admin())
            .andExpect(status().isOk()));
    assertConforms(
        "POST",
        "/admin/v1/moderation/listings/{listingId}/reject",
        "200",
        postJson(
                "/admin/v1/moderation/listings/" + listing + "/reject",
                "{\"reason\":\"blurry\"}",
                admin())
            .andExpect(status().isOk()));
    assertConforms(
        "POST",
        "/admin/v1/moderation/reviews/{reviewId}/hide",
        "200",
        postJson("/admin/v1/moderation/reviews/" + review + "/hide", "{\"reason\":\"x\"}", admin())
            .andExpect(status().isOk()));
  }

  @Test
  void booking_case_and_refund_conform() throws Exception {
    UUID booking = UUID.randomUUID();
    UUID listing = UUID.randomUUID();
    UUID driver = UUID.randomUUID();
    UUID host = UUID.randomUUID();
    UUID intent = UUID.randomUUID();
    STUB.on(
        "GET",
        "/api/v1/bookings/" + booking,
        200,
        Fixtures.booking(booking, listing, driver, host));
    STUB.on(
        "GET",
        "/api/v1/payments/intents?bookingId=" + booking,
        200,
        Fixtures.intent(intent, booking, "CAPTURED", "0.00"));
    STUB.on(
        "GET", "/api/v1/listings/" + listing, 200, Fixtures.listing(listing, host, "PUBLISHED"));
    STUB.on("GET", "/api/v1/profiles/" + driver, 200, Fixtures.profile(driver, "D"));
    STUB.on(
        "POST",
        "/admin/v1/payments/intents/" + intent + "/refunds",
        200,
        Fixtures.intent(intent, booking, "PARTIALLY_REFUNDED", "20.00"));

    assertConforms(
        "GET",
        "/admin/v1/bookings/{bookingId}",
        "200",
        get("/admin/v1/bookings/" + booking, admin()).andExpect(status().isOk()));
    assertConforms(
        "POST",
        "/admin/v1/bookings/{bookingId}/refunds",
        "200",
        perform(
                MockMvcRequestBuilders.post("/admin/v1/bookings/" + booking + "/refunds")
                    .header("Idempotency-Key", "contract-key-1")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"amount\":\"20.00\",\"reason\":\"dispute\"}"),
                admin())
            .andExpect(status().isOk()));
  }

  @Test
  void errors_are_problem_details() throws Exception {
    JsonNode problem =
        body(
            get("/admin/v1/bookings/" + UUID.randomUUID(), admin())
                .andExpect(status().isNotFound()));
    Schema<?> schema =
        API.getPaths()
            .get("/admin/v1/bookings/{bookingId}")
            .getGet()
            .getResponses()
            .get("404")
            .getContent()
            .get("application/problem+json")
            .getSchema();
    List<String> problems = new ArrayList<>();
    conforms(schema, problem, "$", problems);
    assertThat(problems).isEmpty();
  }

  @Test
  void the_checker_itself_catches_violations() throws Exception {
    Schema<?> outcome =
        API.getPaths()
            .get("/admin/v1/moderation/reviews/{reviewId}/hide")
            .getPost()
            .getResponses()
            .get("200")
            .getContent()
            .get("application/json")
            .getSchema();
    List<String> problems = new ArrayList<>();
    conforms(
        outcome,
        json.readTree(
            """
            {"reviewId":"not-a-uuid","direction":"SIDEWAYS","rating":"5","hidden":false,
             "comment":"leaked"}\
            """),
        "$",
        problems);
    assertThat(String.join("\n", problems))
        .contains("$.bookingId: required but missing")
        .contains("$.comment: not documented")
        .contains("$.reviewId: not a uuid")
        .contains("'SIDEWAYS' not in enum")
        .contains("$.rating: string not allowed")
        .contains("$.hidden: must be true");
  }

  // --- schema checking -------------------------------------------------------------------------

  private void assertConforms(String method, String path, String status, ResultActions result)
      throws Exception {
    Operation op =
        API.getPaths().get(path).readOperationsMap().get(PathItem.HttpMethod.valueOf(method));
    Schema<?> schema =
        op.getResponses().get(status).getContent().get("application/json").getSchema();
    List<String> problems = new ArrayList<>();
    conforms(schema, body(result), "$", problems);
    assertThat(problems).as(method + " " + path).isEmpty();
  }

  private static void conforms(Schema<?> schema, JsonNode node, String where, List<String> out) {
    if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
      Schema<?> match =
          schema.getOneOf().stream()
              .filter(alt -> node == null || node.isNull() ? allowsNull(alt) : !onlyNull(alt))
              .findFirst()
              .orElse(null);
      if (match == null) {
        out.add(where + ": matches no oneOf alternative");
      } else if (node != null && !node.isNull()) {
        conforms(match, node, where, out);
      }
      return;
    }
    if (node == null || node.isNull()) {
      if (!allowsNull(schema)) {
        out.add(where + ": null is not allowed");
      }
      return;
    }
    Set<String> types = types(schema);
    if (node.isObject()) {
      if (!types.isEmpty() && !types.contains("object")) {
        out.add(where + ": object not allowed " + types);
      }
      Map<String, Schema> props =
          schema.getProperties() == null ? Map.of() : schema.getProperties();
      if (schema.getRequired() != null) {
        for (String required : schema.getRequired()) {
          if (!node.has(required)) {
            out.add(where + "." + required + ": required but missing");
          }
        }
      }
      for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
        String field = it.next();
        Schema<?> prop = props.get(field);
        if (prop == null) {
          out.add(where + "." + field + ": not documented");
        } else {
          conforms(prop, node.get(field), where + "." + field, out);
        }
      }
    } else if (node.isArray()) {
      if (!types.contains("array")) {
        out.add(where + ": array not allowed " + types);
      }
      for (int i = 0; i < node.size(); i++) {
        conforms(schema.getItems(), node.get(i), where + "[" + i + "]", out);
      }
    } else if (node.isTextual()) {
      if (!types.isEmpty() && !types.contains("string")) {
        out.add(where + ": string not allowed " + types);
      }
      String text = node.asText();
      if (schema.getEnum() != null
          && !schema.getEnum().isEmpty()
          && schema.getEnum().stream().noneMatch(e -> text.equals(String.valueOf(e)))) {
        out.add(where + ": '" + text + "' not in enum");
      }
      if (schema.getPattern() != null
          && !Pattern.compile(schema.getPattern()).matcher(text).find()) {
        out.add(where + ": '" + text + "' does not match " + schema.getPattern());
      }
      if ("uuid".equals(schema.getFormat())) {
        try {
          UUID.fromString(text);
        } catch (IllegalArgumentException e) {
          out.add(where + ": not a uuid");
        }
      }
    } else if (node.isIntegralNumber()) {
      if (!types.isEmpty() && !types.contains("integer") && !types.contains("number")) {
        out.add(where + ": integer not allowed " + types);
      }
    } else if (node.isBoolean()) {
      if (!types.isEmpty() && !types.contains("boolean")) {
        out.add(where + ": boolean not allowed " + types);
      }
      if (schema.getConst() != null && !schema.getConst().equals(node.asBoolean())) {
        out.add(where + ": must be " + schema.getConst());
      }
    }
  }

  private static Set<String> types(Schema<?> schema) {
    if (schema.getTypes() != null) {
      return schema.getTypes();
    }
    return schema.getType() == null ? Set.of() : Set.of(schema.getType());
  }

  private static boolean allowsNull(Schema<?> schema) {
    return types(schema).contains("null") || Boolean.TRUE.equals(schema.getNullable());
  }

  private static boolean onlyNull(Schema<?> schema) {
    return types(schema).equals(Set.of("null"));
  }

  private static String normalize(String path) {
    return path.replaceAll("\\{[^}]+}", "{}");
  }
}
