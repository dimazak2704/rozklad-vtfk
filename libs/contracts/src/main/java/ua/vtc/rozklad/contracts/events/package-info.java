/**
 * Integration events exchanged over RabbitMQ (exchange {@code rozklad.events}, see CONTRACTS.md §7).
 *
 * <p>Every event is a flat JSON object: the envelope fields {@code eventId}, {@code occurredAt},
 * {@code version} followed by the payload fields. Records are immutable; list components are
 * defensively copied and never {@code null} unless documented otherwise.
 */
package ua.vtc.rozklad.contracts.events;
