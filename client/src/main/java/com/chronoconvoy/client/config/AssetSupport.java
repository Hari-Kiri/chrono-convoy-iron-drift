package com.chronoconvoy.client.config;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.headless.HeadlessFiles;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.utils.GdxNativesLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Resolves game assets seamlessly across LWJGL3 desktop execution, headless JUnit tests,
 * and standalone Fat JAR execution.
 */
public final class AssetSupport {

    private static volatile boolean nativesLoaded = false;

    static {
        ensureHeadlessFilesInitialized();
    }

    private AssetSupport() {
    }

    public static synchronized void ensureHeadlessFilesInitialized() {
        if (!nativesLoaded) {
            try {
                GdxNativesLoader.load();
            } catch (Throwable ignored) {
            }
            nativesLoaded = true;
        }
        if (Gdx.files == null) {
            Gdx.files = new HeadlessFiles();
        }
    }

    /**
     * Resolves a relative asset path (e.g., "3d/locomotive.obj", "ui/convoy_hud_skin.json",
     * "audio/adaptive_audio_cues.json") to a readable InputStream.
     */
    public static InputStream openStream(String relativeAssetPath) throws IOException {
        ensureHeadlessFilesInitialized();
        String cleanPath = relativeAssetPath.startsWith("/")
                ? relativeAssetPath.substring(1)
                : relativeAssetPath;

        String[] fsCandidates = {
            "assets/" + cleanPath,
            "client/assets/" + cleanPath,
            "../client/assets/" + cleanPath,
            "../assets/" + cleanPath,
            "/workspace/chrono-convoy-iron-drift/client/assets/" + cleanPath,
            "/workspace/chrono-convoy-iron-drift/assets/" + cleanPath,
            cleanPath
        };
        for (String candidate : fsCandidates) {
            File f = new File(candidate);
            if (f.exists() && f.isFile()) {
                return new FileInputStream(f);
            }
        }

        InputStream cpStream = AssetSupport.class.getResourceAsStream("/" + cleanPath);
        if (cpStream != null) {
            return cpStream;
        }

        if (Gdx.files != null) {
            try {
                FileHandle internal = Gdx.files.internal(cleanPath);
                if (internal.exists()) {
                    return internal.read();
                }
            } catch (Exception ignored) {
            }
        }

        throw new IOException("Asset not found in filesystem or classpath: " + relativeAssetPath);
    }

    public static byte[] readBytes(String relativeAssetPath) throws IOException {
        try (InputStream in = openStream(relativeAssetPath)) {
            return in.readAllBytes();
        }
    }

    public static String readString(String relativeAssetPath) throws IOException {
        return new String(readBytes(relativeAssetPath), StandardCharsets.UTF_8);
    }

    public static boolean exists(String relativeAssetPath) {
        try (InputStream in = openStream(relativeAssetPath)) {
            return in != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static FileHandle resolveFileHandle(String relativeAssetPath) {
        ensureHeadlessFilesInitialized();
        String cleanPath = relativeAssetPath.startsWith("/")
                ? relativeAssetPath.substring(1)
                : relativeAssetPath;

        String[] fsCandidates = {
            "assets/" + cleanPath,
            "client/assets/" + cleanPath,
            "../client/assets/" + cleanPath,
            "../assets/" + cleanPath,
            "/workspace/chrono-convoy-iron-drift/client/assets/" + cleanPath,
            "/workspace/chrono-convoy-iron-drift/assets/" + cleanPath,
            cleanPath
        };
        for (String candidate : fsCandidates) {
            File f = new File(candidate);
            if (f.exists() && f.isFile()) {
                return Gdx.files.absolute(f.getAbsolutePath());
            }
        }

        return Gdx.files.classpath(cleanPath);
    }
}
