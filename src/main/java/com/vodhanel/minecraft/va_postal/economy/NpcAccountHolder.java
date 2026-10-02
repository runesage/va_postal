package com.vodhanel.minecraft.va_postal.economy;

import org.bukkit.OfflinePlayer;

import java.lang.reflect.Proxy;
import java.util.UUID;

/**
 * An {@link OfflinePlayer} that only carries a UUID and a name, used to hand office accounts to the
 * legacy Vault API (which only takes players). This is what Towny does for town accounts.
 * <p>
 * Built as a dynamic proxy so it doesn't break every time Paper adds a method to OfflinePlayer.
 * Anything beyond identity answers "offline, never seen, nothing set".
 */
final class NpcAccountHolder {
    private NpcAccountHolder() {
    }

    static OfflinePlayer of(UUID id, String name) {
        return (OfflinePlayer) Proxy.newProxyInstance(OfflinePlayer.class.getClassLoader(),
                new Class<?>[]{OfflinePlayer.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return id;
                        case "getName":
                            return name;
                        case "equals":
                            return args[0] instanceof OfflinePlayer
                                    && id.equals(((OfflinePlayer) args[0]).getUniqueId());
                        case "hashCode":
                            return id.hashCode();
                        case "toString":
                            return "PostalAccount{" + name + "," + id + "}";
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
