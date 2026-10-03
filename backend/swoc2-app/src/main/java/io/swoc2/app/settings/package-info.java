/**
 * Instance settings (ADM-003, SDX-004, DBG-002): sender ID, own position, aging defaults and
 * debug console availability. Other modules read them through {@link
 * io.swoc2.app.settings.InstanceSettingsService#current()}; changes go through the admin API and
 * are audited.
 */
package io.swoc2.app.settings;
