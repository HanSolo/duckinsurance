package eu.hansolo.duckinsurance.config;

import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Optional;

/**
 * Settings that are edited outside of the code, read with MicroProfile Config. Its sources, strongest first: system properties (-D), environment variables
 * (in the container they come from llm.env and compose.yaml), META-INF/microprofile-config.properties. The names are the environment variable names, for example ANTHROPIC_MODEL.
 */
public final class AppConfig {

    private AppConfig() {
    }


    /** The value, or the default if it is not set or blank. */
    public static String get(String name, String fallback) {
        Optional<String> value = ConfigProvider.getConfig().getOptionalValue(name, String.class);
        return value.map(String::trim).filter(v -> !v.isEmpty()).orElse(fallback);
    }
}
