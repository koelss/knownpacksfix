package dev.knownpacksfix;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@Plugin(
    id = "knownpacksfix",
    name = "KnownPacksFix",
    version = "4.0.0",
    description = "Patches KnownPacksPacket MAX_KNOWN_PACKS limit to allow modded clients to join",
    authors = {"knownpacksfix"}
)
public class KnownPacksFixPlugin {

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public KnownPacksFixPlugin(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        try {
            patchKnownPacksLimit();
        } catch (Exception e) {
            logger.error("[KnownPacksFix] Failed to patch KnownPacksPacket: {}", e.getMessage(), e);
        }
    }

    /**
     * Velocity's KnownPacksPacket has a hardcoded limit (MAX_KNOWN_PACKS = 64).
     * When a modded client sends more packs than that, Velocity throws
     * QuietDecoderException("too many known packs") and disconnects the player.
     *
     * We use reflection to find that field and raise it to Integer.MAX_VALUE.
     */
    private void patchKnownPacksLimit() throws Exception {
        Class<?> packetClass = Class.forName(
            "com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket");

        // Log all fields for diagnostics
        StringBuilder fields = new StringBuilder();
        for (Field f : packetClass.getDeclaredFields()) {
            fields.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
        }
        logger.info("[KnownPacksFix] KnownPacksPacket fields: {}", fields);

        // Strategy 1: try known field names
        String[] candidates = {"MAX_KNOWN_PACKS", "MAXIMUM_KNOWN_PACKS", "MAX_PACKS", "LIMIT", "MAX"};
        for (String name : candidates) {
            try {
                Field f = packetClass.getDeclaredField(name);
                if (f.getType() == int.class) {
                    patchIntField(f);
                    logger.info("[KnownPacksFix] v4.0.0 — Patched '{}'. Modded clients can now join!", name);
                    return;
                }
            } catch (NoSuchFieldException ignored) {}
        }

        // Strategy 2: find any static int field with value 64
        for (Field f : packetClass.getDeclaredFields()) {
            if (f.getType() == int.class && Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                try {
                    int value = f.getInt(null);
                    if (value == 64) {
                        logger.info("[KnownPacksFix] Found field '{}' = 64. Patching...", f.getName());
                        patchIntField(f);
                        logger.info("[KnownPacksFix] v4.0.0 — Patched '{}'. Modded clients can now join!", f.getName());
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }

        throw new IllegalStateException(
            "Could not find the pack limit field (expected a static int = 64) in KnownPacksPacket. " +
            "Fields: " + fields);
    }

    /**
     * Raises a static int field to Integer.MAX_VALUE.
     * Uses Unsafe for final fields, plain reflection otherwise.
     */
    @SuppressWarnings("removal")
    private void patchIntField(Field field) throws Exception {
        field.setAccessible(true);

        if (Modifier.isFinal(field.getModifiers())) {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            long offset = unsafe.staticFieldOffset(field);
            Object base = unsafe.staticFieldBase(field);
            int old = unsafe.getInt(base, offset);
            unsafe.putInt(base, offset, Integer.MAX_VALUE);
            logger.info("[KnownPacksFix] (final) {} → Integer.MAX_VALUE (was {})", field.getName(), old);
        } else {
            int old = field.getInt(null);
            field.setInt(null, Integer.MAX_VALUE);
            logger.info("[KnownPacksFix] {} → Integer.MAX_VALUE (was {})", field.getName(), old);
        }
    }
}
