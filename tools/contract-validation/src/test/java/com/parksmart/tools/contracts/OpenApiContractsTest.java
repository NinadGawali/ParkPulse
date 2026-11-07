package com.parksmart.tools.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Fails the build if any contract in docs/openapi is invalid or breaks the API conventions. */
class OpenApiContractsTest {

  private static final Path CONTRACTS = Path.of("../../docs/openapi");

  static Stream<Path> serviceContracts() throws IOException {
    try (Stream<Path> files = Files.list(CONTRACTS)) {
      return files
          .filter(p -> p.toString().endsWith(".yaml"))
          .filter(p -> !p.getFileName().toString().equals("common.yaml"))
          .sorted()
          .toList()
          .stream();
    }
  }

  @Test
  void contracts_exist_for_phase_one_services() {
    assertThat(CONTRACTS.resolve("identity.yaml")).exists();
    assertThat(CONTRACTS.resolve("listing.yaml")).exists();
    assertThat(CONTRACTS.resolve("booking.yaml")).exists();
    assertThat(CONTRACTS.resolve("payment.yaml")).exists();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("serviceContracts")
  void contract_parses_and_all_references_resolve(Path contract) {
    SwaggerParseResult result = parse(contract);

    assertThat(result.getMessages()).as("parser messages for %s", contract).isEmpty();
    assertThat(result.getOpenAPI()).isNotNull();
    assertThat(result.getOpenAPI().getPaths()).isNotEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("serviceContracts")
  void operations_follow_conventions(Path contract) {
    OpenAPI api = parse(contract).getOpenAPI();
    List<String> problems = new ArrayList<>();

    api.getPaths()
        .forEach((path, item) -> {
          if (!path.startsWith("/api/v1/")
              && !path.startsWith("/admin/v1/")
              && !path.startsWith("/internal/v1/")
              && !path.startsWith("/.well-known/")) {
            problems.add(path + ": path must start with /api/v1, /admin/v1 or /internal/v1");
          }
          for (Map.Entry<PathItem.HttpMethod, Operation> entry :
              item.readOperationsMap().entrySet()) {
            Operation op = entry.getValue();
            String where = entry.getKey() + " " + path;
            if (op.getOperationId() == null) {
              problems.add(where + ": missing operationId");
            }
            if (requiresAuth(api, op) && !op.getResponses().containsKey("401")) {
              problems.add(where + ": secured operation must document 401");
            }
            if (path.startsWith("/admin/v1/") && !op.getResponses().containsKey("403")) {
              problems.add(where + ": admin operation must document 403");
            }
          }
        });

    assertThat(problems).isEmpty();
  }

  @Test
  void operation_ids_are_unique_across_services() throws IOException {
    Set<String> seen = new HashSet<>();
    List<String> duplicates = new ArrayList<>();
    for (Path contract : serviceContracts().toList()) {
      parse(contract)
          .getOpenAPI()
          .getPaths()
          .values()
          .forEach(item ->
              item.readOperations()
                  .forEach(op -> {
                    if (!seen.add(op.getOperationId())) {
                      duplicates.add(op.getOperationId());
                    }
                  }));
    }
    assertThat(duplicates).isEmpty();
  }

  private static boolean requiresAuth(OpenAPI api, Operation op) {
    List<SecurityRequirement> security =
        op.getSecurity() != null ? op.getSecurity() : api.getSecurity();
    if (security == null || security.isEmpty()) {
      return false;
    }
    // An empty requirement ({}) means anonymous access is allowed.
    return security.stream().noneMatch(Map::isEmpty);
  }

  private static SwaggerParseResult parse(Path contract) {
    ParseOptions options = new ParseOptions();
    options.setResolve(true);
    return new OpenAPIV3Parser()
        .readLocation(contract.toAbsolutePath().normalize().toUri().toString(), null, options);
  }
}
