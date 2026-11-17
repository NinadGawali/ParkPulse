/**
 * Versioned event contracts shared by all ParkSmart services. No entities, no behaviour beyond
 * serialization.
 *
 * <p>Every message on Kafka is an {@link com.parksmart.common.events.EventEnvelope} whose payload
 * is one of the records registered in {@link com.parksmart.common.events.EventCatalog}. The Kafka
 * record key is {@link com.parksmart.common.events.EventPayload#partitionKey()} so events for one
 * aggregate stay ordered.
 *
 * <p>Evolution rules: adding an optional field keeps the version; renaming, removing, or changing
 * the meaning of a field requires a new version registered alongside the old one until all
 * consumers have moved. Consumers ignore unknown fields. JSON Schemas for every payload live in
 * {@code src/main/resources/event-schemas} and are checked by a test.
 */
package com.parksmart.common.events;
