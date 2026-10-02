/**
 * Declarative description of every SEDAP-Express message type (ICD §5, §6): which fields it has,
 * their types, units, value ranges and code tables. The codec ({@code io.swoc2.sedap.codec})
 * interprets these schemas instead of hand-coding each message, and the COMMAND schemas
 * ({@link io.swoc2.sedap.schema.CommandSchemas}) are what the tasking wizard (TSK-002) will
 * generate its forms from. No Spring, no I/O.
 */
package io.swoc2.sedap.schema;
