package com.parksmart.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EventCatalogTest {

  @Test
  void every_payload_record_on_the_classpath_is_registered() throws Exception {
    Set<Class<?>> registered =
        EventCatalog.ALL.stream().map(EventDefinition::payloadClass).collect(Collectors.toSet());

    assertThat(payloadClassesOnClasspath()).isNotEmpty().allMatch(registered::contains);
  }

  @Test
  void types_and_topics_are_unique_and_valid() {
    List<String> types = EventCatalog.ALL.stream().map(EventDefinition::type).toList();
    assertThat(types).doesNotHaveDuplicates();

    Set<String> knownTopics =
        Set.of(
            Topics.IDENTITY_ACCOUNT,
            Topics.IDENTITY_VERIFICATION_DELIVERY,
            Topics.PROFILE,
            Topics.LISTING,
            Topics.BOOKING,
            Topics.PAYMENT,
            Topics.PAYOUT,
            Topics.REVIEW);
    assertThat(EventCatalog.ALL).allMatch(d -> knownTopics.contains(d.topic()));
  }

  @Test
  void unregistered_payload_is_rejected() {
    record Stray(String id) implements EventPayload {
      @Override
      public String partitionKey() {
        return id;
      }
    }
    assertThatThrownBy(() -> EventCatalog.forPayload(Stray.class))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void dead_letter_topic_uses_spring_kafka_default_suffix() {
    assertThat(Topics.deadLetterTopic(Topics.BOOKING)).isEqualTo("booking.booking.v1.DLT");
  }

  private static List<Class<?>> payloadClassesOnClasspath() throws IOException, URISyntaxException {
    Path root =
        Path.of(EventPayload.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
          .map(p -> root.relativize(p).toString().replace('\\', '/').replace('/', '.'))
          .map(name -> name.substring(0, name.length() - ".class".length()))
          .map(EventCatalogTest::load)
          .filter(c -> EventPayload.class.isAssignableFrom(c) && c.isRecord())
          .collect(Collectors.toList());
    }
  }

  private static Class<?> load(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    }
  }
}
