package com.dwinovo.numen.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Identity constants for the {@code numen-core} tool pack. The engine
 * ({@code numen-api}) has its own {@code com.dwinovo.numen.Constants}; this is
 * deliberately a separate type in a separate package so the two mods never put
 * the same fully-qualified class on the runtime classpath.
 */
public final class Constants {

    public static final String MOD_ID = "numen";
    public static final String MOD_NAME = "Numen";
    public static final Logger LOG = LoggerFactory.getLogger("NumenCore");

    private Constants() {}
}
