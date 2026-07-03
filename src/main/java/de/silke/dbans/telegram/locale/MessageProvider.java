package de.silke.dbans.telegram.locale;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnmodifiableView;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class MessageProvider {

    private static final Logger log = Logger.getLogger("dbans-telegram-addon");

    private final Map<String, String> templates;
    private final Map<String, String> typeNames;
    private final String permanentText;

    public MessageProvider(@NotNull Plugin plugin, @NotNull SupportedLocale locale) {
        YamlConfiguration yaml = loadYaml(plugin, locale);
        this.templates = buildTemplates(yaml);
        this.typeNames = buildTypeNames(yaml);
        this.permanentText = yaml != null ? yaml.getString("permanent", "permanent") : "permanent";
    }

    @Nullable
    private static YamlConfiguration loadYaml(@NotNull Plugin plugin, @NotNull SupportedLocale locale) {
        String resourcePath = "locale/" + locale.getCode() + ".yml";
        File file = new File(plugin.getDataFolder(), resourcePath);
        return file.exists()
                ? YamlConfiguration.loadConfiguration(file)
                : loadFromStream(plugin.getResource(resourcePath), resourcePath);
    }

    @SuppressWarnings("MethodWithMultipleReturnPoints")
    @Nullable
    private static YamlConfiguration loadFromStream(@Nullable InputStream stream, @NotNull String path) {
        if (stream == null) {
            log.severe("Locale file not found: " + path + ". Falling back to empty templates");
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Failed to read locale file: " + path, e);
            return null;
        }
    }

    private static @NotNull @UnmodifiableView Map<String, String> buildTemplates(
            @Nullable YamlConfiguration yaml
    ) {
        Map<String, String> map = new HashMap<>();
        if (yaml != null) {
            for (MessageKey key : MessageKey.values()) {
                map.put(key.getYamlKey(), yaml.getString(key.getYamlKey(), key.getYamlKey()));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static @NotNull @UnmodifiableView Map<String, String> buildTypeNames(
            @Nullable YamlConfiguration yaml
    ) {
        Map<String, String> map = new HashMap<>();
        if (yaml != null) {
            var section = yaml.getConfigurationSection("types");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    map.put(key, section.getString(key, key));
                }
            }
        }
        return Collections.unmodifiableMap(map);
    }

    public @NotNull String format(@NotNull MessageKey key, @NotNull Map<String, String> vars) {
        String template = templates.getOrDefault(key.getYamlKey(), key.getYamlKey());
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            template = template.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return template.strip();
    }

    public @NotNull String permanent() {
        return permanentText;
    }

    public @NotNull String typeName(@NotNull String rawName) {
        return typeNames.getOrDefault(rawName, rawName);
    }
}
