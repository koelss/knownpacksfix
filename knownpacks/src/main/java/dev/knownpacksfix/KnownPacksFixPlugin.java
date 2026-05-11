package dev.knownpacksfix;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;

@Plugin(
    id = "knownpacksfix",
    name = "KnownPacksFix",
    version = "1.1.0",
    description = "Patches KnownPacksPacket MAX_KNOWN_PACKS limit to allow modded clients to join",
    authors = {"Koels"}
)
public class KnownPacksFixPlugin {

    private static final String PACKET_CLASS =
        "com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket";
    private static final String[] FIELD_CANDIDATES =
        {"MAX_KNOWN_PACKS", "MAXIMUM_KNOWN_PACKS", "MAX_PACKS", "LIMIT", "MAX"};
    private static final int VANILLA_LIMIT = 64;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public KnownPacksFixPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        PluginConfig config = PluginConfig.load(dataDirectory, logger);
        try {
            patchKnownPacksLimit(config);
        } catch (Exception e) {
            logger.error("[KnownPacksFix] Failed to patch KnownPacksPacket: {}", e.getMessage(), e);
        }
    }

    /**
     * Velocity's KnownPacksPacket has a hardcoded limit (MAX_KNOWN_PACKS = 64).
     * When a modded client sends more packs than that, Velocity throws
     * QuietDecoderException("too many known packs") and disconnects the player.
     *
     * We use reflection to find that field and raise it to the configured limit.
     */
    private void patchKnownPacksLimit(PluginConfig config) throws Exception {
        Class<?> packetClass = Class.forName(PACKET_CLASS);

        if (config.isLogFields()) {
            StringBuilder fieldList = new StringBuilder();
            for (Field f : packetClass.getDeclaredFields()) {
                fieldList.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
            }
            logger.info("[KnownPacksFix] KnownPacksPacket fields: {}", fieldList);
        }

        // Strategy 1: try well-known field names first
        for (String candidateName : FIELD_CANDIDATES) {
            try {
                Field field = packetClass.getDeclaredField(candidateName);
                if (field.getType() == int.class) {
                    patchIntField(field, config);
                    logger.info("[KnownPacksFix] v1.1.0 — Patched '{}' to {}. Modded clients can now join!",
                        candidateName, config.getPackLimit());
                    return;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }

        // Strategy 2: scan for any static int field holding the vanilla default value
        for (Field field : packetClass.getDeclaredFields()) {
            if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            try {
                if (field.getInt(null) == VANILLA_LIMIT) {
                    if (config.isDebug()) {
                        logger.info("[KnownPacksFix] Found field '{}' = {}. Patching...",
                            field.getName(), VANILLA_LIMIT);
                    }
                    patchIntField(field, config);
                    logger.info("[KnownPacksFix] v1.1.0 — Patched '{}' to {}. Modded clients can now join!",
                        field.getName(), config.getPackLimit());
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        // Build field summary for the error message
        StringBuilder fieldList = new StringBuilder();
        for (Field f : packetClass.getDeclaredFields()) {
            fieldList.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
        }
        throw new IllegalStateException(
            "Could not find the pack limit field (expected a static int = " + VANILLA_LIMIT
                + ") in KnownPacksPacket. Fields seen: " + fieldList);
    }

    /**
     * Raises a static int field to the configured pack limit.
     * Uses Unsafe for final fields, plain reflection for non-final fields.
     */
    @SuppressWarnings("removal")
    private void patchIntField(Field field, PluginConfig config) throws Exception {
        field.setAccessible(true);
        int targetLimit = config.getPackLimit();

        if (Modifier.isFinal(field.getModifiers())) {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            long offset = unsafe.staticFieldOffset(field);
            Object base = unsafe.staticFieldBase(field);
            int oldValue = unsafe.getInt(base, offset);
            unsafe.putInt(base, offset, targetLimit);
            if (config.isDebug()) {
                logger.info("[KnownPacksFix] (final) {} {} → {}", field.getName(), oldValue, targetLimit);
            }
        } else {
            int oldValue = field.getInt(null);
            field.setInt(null, targetLimit);
            if (config.isDebug()) {
                logger.info("[KnownPacksFix] {} {} → {}", field.getName(), oldValue, targetLimit);
            }
        }
    }
}
