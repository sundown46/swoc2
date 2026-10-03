/**
 * Audit module (AUTH-005, ADM-006, CLAUDE.md principle 10): append-only record of every
 * state-changing action - who, what, when, before/after. Other modules use {@link
 * io.swoc2.app.audit.AuditLog} only; storage and the query API are internal.
 */
package io.swoc2.app.audit;
