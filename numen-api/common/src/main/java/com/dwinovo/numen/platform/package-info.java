/**
 * <strong>Public API:</strong> {@link Services} — the cross-loader SPI locator —
 * and the interfaces under {@code platform.services} it serves (network channel,
 * config, platform info, block-capability reading, sound factory). A tool pack
 * reads loader-neutral capabilities through here (e.g. inspecting a machine's
 * items/fluids/energy without opening its GUI) instead of binding to a loader.
 */
package com.dwinovo.numen.platform;
