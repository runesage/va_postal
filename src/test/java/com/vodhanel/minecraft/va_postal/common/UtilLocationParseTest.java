package com.vodhanel.minecraft.va_postal.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

/** Malformed strings must fail before any world lookup, so no running server is needed. */
class UtilLocationParseTest {
    @Test
    void str2blockReturnsNullForMalformedInput() {
        assertNull(Util.str2block(null));
        assertNull(Util.str2block("null"));
        assertNull(Util.str2block("   "));
        assertNull(Util.str2block("world"));
        assertNull(Util.str2block("world,1,2"));
        assertNull(Util.str2block("world,a,b,c"));
    }
}
