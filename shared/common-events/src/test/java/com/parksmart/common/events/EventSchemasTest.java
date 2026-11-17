package com.parksmart.common.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.victools.jsonschema.generator.Option;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Keeps the committed JSON Schemas in sync with the payload records. To regenerate after changing a
 * record: {@code ./mvnw -pl shared/common-events test -Devents.updateSchemas=true}.
 */
class EventSchemasTest {

  private static final Path SCHEMA_DIR = Path.of("src/main/resources");
  private static final boolean UPDATE = Boolean.getBoolean("events.updateSchemas");
  private static final SchemaGenerator GENERATOR = generator();

  static Stream<EventDefinition> definitions() {
    return EventCatalog.ALL.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("definitions")
  void committed_schema_matches_payload_record(EventDefinition definition) throws IOException {
    String expected = render(definition);
    Path file = SCHEMA_DIR.resolve(definition.schemaResource());

    if (UPDATE) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, expected, StandardCharsets.UTF_8);
    }

    assertThat(file)
        .as("Schema missing or stale; regenerate with -Devents.updateSchemas=true")
        .exists();
    assertThat(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n"))
        .isEqualTo(expected);
  }

  private static String render(EventDefinition definition) throws IOException {
    ObjectNode schema = GENERATOR.generateSchema(definition.payloadClass());
    schema.put("$id", "https://parksmart.dev/" + definition.schemaResource());
    schema.put("title", definition.type() + " v" + definition.version());
    schema.put("x-topic", definition.topic());
    String json = EventJson.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(schema);
    // Pretty printer uses the platform line separator; schemas are committed with LF.
    return json.replace("\r\n", "\n") + "\n";
  }

  private static SchemaGenerator generator() {
    SchemaGeneratorConfigBuilder builder =
        new SchemaGeneratorConfigBuilder(SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON)
            .with(new JacksonModule())
            .with(Option.FORBIDDEN_ADDITIONAL_PROPERTIES_BY_DEFAULT)
            .with(Option.NULLABLE_FIELDS_BY_DEFAULT)
            .without(Option.SCHEMA_VERSION_INDICATOR);
    builder
        .forFields()
        .withRequiredCheck(field -> true)
        .withNullableCheck(
            field -> field.getAnnotationConsideringFieldAndGetter(NullableField.class) != null);
    builder
        .forTypesInGeneral()
        .withStringFormatResolver(scope -> {
          Class<?> type = scope.getType().getErasedType();
          if (type == UUID.class) {
            return "uuid";
          }
          if (type == Instant.class) {
            return "date-time";
          }
          if (type == LocalDate.class) {
            return "date";
          }
          return null;
        });
    builder
        .forFields()
        .withTargetTypeOverridesResolver(field ->
            field.getType().getErasedType() == BigDecimal.class
                ? java.util.List.of(field.getContext().resolve(String.class))
                : null)
        .withStringPatternResolver(field ->
            field.getType().getErasedType() == BigDecimal.class
                ? "^-?\\d{1,8}(\\.\\d{1,2})?$"
                : null);
    return new SchemaGenerator(builder.build());
  }
}
