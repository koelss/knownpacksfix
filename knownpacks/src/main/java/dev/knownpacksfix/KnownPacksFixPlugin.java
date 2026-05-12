package dev.knownpacksfix;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Plugin(
    id = "knownpacksfix",
    name = "KnownPacksFix",
    version = "1.2.0",
    description = "Patches KnownPacksPacket MAX_KNOWN_PACKS limit to allow modded clients to join",
    authors = {"Koels"}
)
public class KnownPacksFixPlugin {

    private static final String PACKET_CLASS =
        "com.velocitypowered.proxy.protocol.packet.config.KnownPacksPacket";
    private static final String[] BUILT_IN_CANDIDATES =
        {"MAX_KNOWN_PACKS", "MAXIMUM_KNOWN_PACKS", "MAX_PACKS", "LIMIT", "MAX"};
    private static final int VANILLA_LIMIT = 64;
    private static final int WARN_LIMIT_LOW  = 64;
    private static final int WARN_LIMIT_HIGH = 10000;

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    // Mutable state — updated on each reload
    private PluginConfig config;
    private boolean patchApplied = false;
    private final List<PatchResult> patchResults  = new ArrayList<>();
    private final List<Field>       patchedFields = new ArrayList<>();

    @Inject
    public KnownPacksFixPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialize(ProxyInitializeEvent event) {
        config = PluginConfig.load(dataDirectory, logger);
        validateConfig(config);
        logVelocityVersion();
        applyPatch(config);
        registerCommand();
    }

    // -------------------------------------------------------------------------
    // Public API for KnownPacksCommand
    // -------------------------------------------------------------------------

    public void reloadPlugin(CommandSource source) {
        source.sendMessage(Component.text("[KnownPacksFix] Reloading config...", NamedTextColor.YELLOW));
        config = PluginConfig.load(dataDirectory, logger);
        validateConfig(config);
        patchResults.clear();
        patchApplied = false;
        applyPatch(config);
        if (patchApplied) {
            source.sendMessage(Component.text(
                "[KnownPacksFix] Reload complete. Pack limit is now " + config.getPackLimit() + ".",
                NamedTextColor.GREEN));
        } else {
            source.sendMessage(Component.text(
                "[KnownPacksFix] Reload complete but patch failed — check console for details.",
                NamedTextColor.RED));
        }
    }

    public void sendStatus(CommandSource source) {
        String velocityVer = getVelocityVersion();
        source.sendMessage(Component.text("--- KnownPacksFix v1.2.0 ---", NamedTextColor.GOLD));
        source.sendMessage(Component.text("Velocity version : ", NamedTextColor.GRAY)
            .append(Component.text(velocityVer, NamedTextColor.WHITE)));
        source.sendMessage(Component.text("Patch applied    : ", NamedTextColor.GRAY)
            .append(Component.text(
                patchApplied ? "Yes" : "No",
                patchApplied ? NamedTextColor.GREEN : NamedTextColor.RED)));
        source.sendMessage(Component.text("Active limit     : ", NamedTextColor.GRAY)
            .append(Component.text(String.valueOf(config.getPackLimit()), NamedTextColor.YELLOW)));

        if (patchResults.isEmpty()) {
            source.sendMessage(Component.text("No fields patched.", NamedTextColor.RED));
        } else {
            for (PatchResult r : patchResults) {
                String type     = r.wasFinalField() ? " [final/Unsafe]" : " [reflection]";
                String verified = r.isVerified()    ? " ✔ verified"     : " ✘ NOT verified";
                source.sendMessage(Component.text(
                    "  " + r.getFieldName() + ": " + r.getOldValue() + " → " + r.getNewValue()
                        + type + verified,
                    r.isVerified() ? NamedTextColor.GREEN : NamedTextColor.RED));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Patch logic
    // -------------------------------------------------------------------------

    private void applyPatch(PluginConfig cfg) {
        try {
            if (!patchedFields.isEmpty()) {
                // Reload path: re-patch the already-discovered fields directly
                rePatchKnownFields(cfg);
            } else {
                // First run: full discovery
                discoverAndPatch(cfg);
            }
        } catch (ClassNotFoundException e) {
            logger.error("[KnownPacksFix] KnownPacksPacket class not found — this Velocity version may not be supported.");
            logger.error("[KnownPacksFix] Running Velocity {}. Please report this at the plugin page.",
                getVelocityVersion());
        } catch (Exception e) {
            logger.error("[KnownPacksFix] Failed to patch KnownPacksPacket: {}", e.getMessage(), e);
            logger.error("[KnownPacksFix] Running Velocity {}. Please report this at the plugin page.",
                getVelocityVersion());
        }
    }

    /**
     * Re-patches fields that were already found on a previous run.
     * Used during reloads so we skip the discovery step entirely.
     */
    private void rePatchKnownFields(PluginConfig cfg) throws Exception {
        for (Field field : patchedFields) {
            PatchResult result = patchIntField(field, cfg);
            patchResults.add(result);
            logResult(result);
        }
        patchApplied = !patchResults.isEmpty();
    }

    /**
     * Full field discovery + patching. Tries named candidates first,
     * then falls back to scanning for any static int == VANILLA_LIMIT.
     */
    private void discoverAndPatch(PluginConfig cfg) throws Exception {
        Class<?> packetClass = Class.forName(PACKET_CLASS);

        if (cfg.isLogFields()) {
            StringBuilder fieldList = new StringBuilder();
            for (Field f : packetClass.getDeclaredFields()) {
                fieldList.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
            }
            logger.info("[KnownPacksFix] KnownPacksPacket fields: {}", fieldList);
        }

        // Build full candidate list: built-in + custom from config
        List<String> candidates = new ArrayList<>(Arrays.asList(BUILT_IN_CANDIDATES));
        candidates.addAll(cfg.getCustomFieldNames());

        // Strategy 1: try all candidate field names
        for (String candidateName : candidates) {
            try {
                Field field = packetClass.getDeclaredField(candidateName);
                if (field.getType() == int.class) {
                    PatchResult result = patchIntField(field, cfg);
                    patchResults.add(result);
                    patchedFields.add(field);
                    logResult(result);
                    patchApplied = true;
                    if (!cfg.isPatchAllMatches()) return;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }

        if (patchApplied && !cfg.isPatchAllMatches()) return;

        // Strategy 2: scan for any static int field holding the vanilla default value
        for (Field field : packetClass.getDeclaredFields()) {
            if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            try {
                if (field.getInt(null) == VANILLA_LIMIT) {
                    if (cfg.isDebug()) {
                        logger.info("[KnownPacksFix] Fallback: found field '{}' = {}. Patching...",
                            field.getName(), VANILLA_LIMIT);
                    }
                    PatchResult result = patchIntField(field, cfg);
                    patchResults.add(result);
                    patchedFields.add(field);
                    logResult(result);
                    patchApplied = true;
                    if (!cfg.isPatchAllMatches()) return;
                }
            } catch (Exception ignored) {
            }
        }

        if (!patchApplied) {
            StringBuilder fieldList = new StringBuilder();
            for (Field f : packetClass.getDeclaredFields()) {
                fieldList.append(f.getName()).append("(").append(f.getType().getSimpleName()).append(") ");
            }
            throw new IllegalStateException(
                "Could not find the pack limit field (expected a static int = " + VANILLA_LIMIT
                    + ") in KnownPacksPacket. Velocity: " + getVelocityVersion()
                    + " | Fields seen: " + fieldList);
        }
    }

    /**
     * Patches a single static int field to the configured limit and verifies the write.
     * Uses Unsafe for final fields; plain reflection for non-final fields.
     */
    @SuppressWarnings("removal")
    private PatchResult patchIntField(Field field, PluginConfig cfg) throws Exception {
        field.setAccessible(true);
        int targetLimit = cfg.getPackLimit();
        boolean isFinal = Modifier.isFinal(field.getModifiers());
        int oldValue;
        int readBack;

        if (isFinal) {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            long offset = unsafe.staticFieldOffset(field);
            Object base  = unsafe.staticFieldBase(field);
            oldValue = unsafe.getInt(base, offset);
            unsafe.putInt(base, offset, targetLimit);
            readBack = unsafe.getInt(base, offset);
        } else {
            oldValue = field.getInt(null);
            field.setInt(null, targetLimit);
            readBack = field.getInt(null);
        }

        boolean verified = readBack == targetLimit;

        if (cfg.isDebug()) {
            logger.info("[KnownPacksFix] {} {} {} → {} (read back: {}, verified: {})",
                isFinal ? "(final/Unsafe)" : "(reflection)",
                field.getName(), oldValue, targetLimit, readBack, verified);
        }

        if (!verified) {
            logger.warn("[KnownPacksFix] Verification failed for '{}': wrote {} but read back {}. " +
                "Another system may be overriding the value.", field.getName(), targetLimit, readBack);
        }

        return new PatchResult(field.getName(), oldValue, targetLimit, verified, isFinal);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void logResult(PatchResult result) {
        if (result.isVerified()) {
            logger.info("[KnownPacksFix] v1.2.0 — Patched '{}' ({} → {}). Modded clients can now join!",
                result.getFieldName(), result.getOldValue(), result.getNewValue());
        } else {
            logger.warn("[KnownPacksFix] Patched '{}' but verification failed — value may not have changed!",
                result.getFieldName());
        }
    }

    private void validateConfig(PluginConfig cfg) {
        if (cfg.getPackLimit() <= WARN_LIMIT_LOW) {
            logger.warn("[KnownPacksFix] pack-limit ({}) is at or below Velocity's default of {}. " +
                "Modded clients may still be disconnected!", cfg.getPackLimit(), VANILLA_LIMIT);
        } else if (cfg.getPackLimit() > WARN_LIMIT_HIGH) {
            logger.warn("[KnownPacksFix] pack-limit ({}) is very high and may cause performance issues.",
                cfg.getPackLimit());
        }
    }

    private void logVelocityVersion() {
        logger.info("[KnownPacksFix] Detected Velocity version: {}", getVelocityVersion());
    }

    private String getVelocityVersion() {
        try {
            return server.getVersion().getVersion();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void registerCommand() {
        server.getCommandManager().register(
            server.getCommandManager().metaBuilder("knownpacksfix")
                .aliases("kpf")
                .plugin(this)
                .build(),
            new KnownPacksCommand(this)
        );
        logger.info("[KnownPacksFix] Commands registered: /knownpacksfix, /kpf");
    }
}
