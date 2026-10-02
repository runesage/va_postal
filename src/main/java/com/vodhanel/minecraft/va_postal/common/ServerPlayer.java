package com.vodhanel.minecraft.va_postal.common;

import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * The "Server" pseudo-player used as the owner of anything no real player owns.
 * <p>
 * Only identity is meaningful: the UUID, and "Server" as every name. Every other Player method
 * answers null/false/0. Built as a dynamic proxy so it doesn't have to track every method Paper adds
 * to {@link Player}.
 */
public final class ServerPlayer {
    private static final String NAME = "Server";

    private ServerPlayer() {
    }

    public static Player create(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return id;
                        case "getName":
                        case "getDisplayName":
                        case "getPlayerListName":
                        case "getCustomName":
                            return NAME;
                        case "equals":
                            return proxy == args[0];
                        case "hashCode":
                            return id.hashCode();
                        case "toString":
                            return NAME;
                        default:
                            return default_value(method.getReturnType());
                    }
                });
    }

    private static Object default_value(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0F;
        }
        if (type == double.class) {
            return 0D;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
