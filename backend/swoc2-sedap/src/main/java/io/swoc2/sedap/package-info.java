/**
 * SEDAP-Express codec and mapping between SEDAP-Express messages and the domain model
 * (ARCHITECTURE §3, §11; ICD in {@code docs/icd/}).
 *
 * <p>Wraps the reference library {@code io.github.uniity-team:sedapexpress}. Where the
 * reference library is stricter than needed, a tolerant pre-parser keeps unknown fields raw
 * (REQUIREMENTS SDX-001) instead of failing. The actual codec integration is Spike C
 * (ROADMAP P0 item 9) and is not part of this skeleton - see docs/OPEN_QUESTIONS.md Q-010 for
 * why the dependency itself is not wired up yet.
 */
package io.swoc2.sedap;
