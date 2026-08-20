package dev.vcore.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vcore.security")
public record SecurityProperties(String bffSharedSecret) {

    public SecurityProperties {
        bffSharedSecret = bffSharedSecret == null ? "" : bffSharedSecret;
    }
}
