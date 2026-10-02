package dev.daze.worldmap.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import dev.daze.worldmap.SafeFiles;
import dev.daze.worldmap.platform.Platform;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/** Настройки клиента: config/worldmap.json. */
public final class ClientConfig {
    public boolean compass = true;
    public boolean showPlayers = true;
    public boolean fastAnimations = false;
    public boolean grid = false;
    public boolean deathMarks = true;
    /** 0 — авто, иначе фиксированный масштаб интерфейса карты. */
    public float uiScale = 0;
    public boolean sidebar = true;
    public float mapZoom = 2f;

    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ClientConfig instance;

    public static ClientConfig get() {
        if (instance == null) {
            Path f = file();
            try {
                if (Files.exists(f)) instance = GSON.fromJson(Files.readString(f), ClientConfig.class);
            } catch (Exception e) {
                LOG.warn("worldmap: ошибка в {}, используются настройки по умолчанию", f, e);
            }
            if (instance == null) instance = new ClientConfig();
        }
        return instance;
    }

    public void save() {
        try {
            SafeFiles.writeString(file(), GSON.toJson(this));
        } catch (Exception e) {
            LOG.warn("worldmap: не удалось сохранить настройки", e);
        }
    }

    private static Path file() {
        return Platform.get().configDir().resolve("worldmap.json");
    }
}
