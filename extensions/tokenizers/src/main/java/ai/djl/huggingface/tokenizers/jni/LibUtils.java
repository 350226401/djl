/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package ai.djl.huggingface.tokenizers.jni;

import ai.djl.engine.EngineException;
import ai.djl.util.ClassLoaderUtils;
import ai.djl.util.Platform;
import ai.djl.util.Utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Utilities for finding the Huggingface tokenizer native binary on the System. */
@SuppressWarnings("MissingJavadocMethod")
public final class LibUtils {

    private static final Logger logger = LoggerFactory.getLogger(LibUtils.class);

    private static final String LIB_NAME = System.mapLibraryName("tokenizers");
    private static final Pattern VERSION_PATTERN =
            Pattern.compile(
                    "(\\d+\\.\\d+\\.\\d+(-[a-z]+)?)-(\\d+\\.\\d+\\.\\d+)(-SNAPSHOT)?(-\\d+)?");
    private static final String FLAVOR_CU124 = "cu124";

    private static EngineException exception;

    static {
        try {
            loadLibrary();
        } catch (RuntimeException e) {
            exception = new EngineException("Failed to load Huggingface native library.", e);
        }
    }

    private LibUtils() {}

    public static void checkStatus() {
        if (exception != null) {
            throw exception;
        }
    }

    private static void loadLibrary() {
        String[] libs;
        if (System.getProperty("os.name").startsWith("Windows")) {
            libs =
                    new String[] {
                        "libwinpthread-1.dll", "libgcc_s_seh-1.dll", "libstdc++-6.dll", LIB_NAME
                    };
        } else {
            libs = new String[] {LIB_NAME};
        }

        Path dir = copyJniLibrary(libs);
        logger.debug("Loading huggingface library from: {}", dir);

        for (String libName : libs) {
            String path = dir.resolve(libName).toString();
            logger.debug("Loading native library: {}", path);
            String nativeHelper = System.getProperty("ai.djl.huggingface.native_helper");
            if (nativeHelper != null && !nativeHelper.isEmpty()) {
                ClassLoaderUtils.nativeLoad(nativeHelper, path);
            } else {
                System.load(path); // NOPMD
            }
        }
    }

    private static Path copyJniLibrary(String[] libs) {
        Path cacheDir = Utils.getEngineCacheDir("tokenizers");
        Platform platform = Platform.detectPlatform("tokenizers");
        String os = platform.getOsPrefix();
        String classifier = platform.getClassifier();
        String flavor = platform.getFlavor();
        String version = platform.getVersion();
        Path dir = cacheDir.resolve(version + '-' + flavor + '-' + classifier);
        Path path = dir.resolve(LIB_NAME);
        logger.debug("Using cache dir: {}", dir);
        if (Files.exists(path)) {
            return dir.toAbsolutePath();
        }

        // For Linux cuda 12.x, download JNI library
        if (flavor.startsWith("cu12") && !"win".equals(os)) {
            Matcher matcher = VERSION_PATTERN.matcher(version);
            if (!matcher.matches()) {
                throw new EngineException("Unexpected version: " + version);
            }
            String jniVersion = matcher.group(1);
            String djlVersion = matcher.group(3);

            downloadJniLib(dir, path, djlVersion, jniVersion, classifier, FLAVOR_CU124);
            return dir.toAbsolutePath();
        }

        // Extract JNI library from classpath
        Path tmp = null;
        try {
            Files.createDirectories(cacheDir);
            tmp = Files.createTempDirectory(cacheDir, "tmp");

            for (String libName : libs) {
                String libPath = "native/lib/" + classifier + "/" + flavor + "/" + libName;
                logger.info("Extracting {} to cache ...", libPath);
                try (InputStream is = ClassLoaderUtils.getResourceAsStream(libPath)) {
                    Path target = tmp.resolve(libName);
                    Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Utils.moveQuietly(tmp, dir);
            return dir.toAbsolutePath();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot copy jni files", e);
        } finally {
            if (tmp != null) {
                Utils.deleteQuietly(tmp);
            }
        }
    }

    private static void downloadJniLib(
            Path cacheDir,
            Path path,
            String djlVersion,
            String version,
            String classifier,
            String flavor) {
        String url =
                "https://publish.djl.ai/tokenizers/"
                        + version
                        + "/jnilib/"
                        + djlVersion
                        + '/'
                        + classifier
                        + '/'
                        + flavor
                        + '/'
                        + LIB_NAME;
        logger.info("Downloading jni {} to cache ...", url);
        Path tmp = null;
        try (InputStream is = Utils.openUrl(url)) {
            Files.createDirectories(cacheDir);
            tmp = Files.createTempFile(cacheDir, "jni", "tmp");
            Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
            Utils.moveQuietly(tmp, path);
        } catch (IOException e) {
            throw new EngineException("Cannot download jni files: " + url, e);
        } finally {
            if (tmp != null) {
                Utils.deleteQuietly(tmp);
            }
        }
    }
}
