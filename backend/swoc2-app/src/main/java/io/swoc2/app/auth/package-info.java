/**
 * Authentication and authorization: the BFF OIDC login against Keycloak (ADR 0004), mapping
 * Keycloak client roles to Spring authorities with the role hierarchy from REQUIREMENTS
 * AUTH-002 (admin &gt; commander &gt; operator &gt; viewer), and the role-protected test
 * endpoints used to verify that mapping (ROADMAP P0 item 5).
 */
package io.swoc2.app.auth;
