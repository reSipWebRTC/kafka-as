package com.kafkaasr.tts.storage;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "tts.storage")
public class TtsStorageProperties {

    private boolean enabled = false;

    @NotBlank
    private String provider = "none";

    private String bucket = "";

    @NotBlank
    private String region = "us-east-1";

    private String endpoint = "";

    private String accessKey = "";

    private String secretKey = "";

    private boolean pathStyleAccess = true;

    @NotBlank
    private String keyPrefix = "tts";

    private String publicBaseUrl = "";

    @NotBlank
    private String objectSuffix = "wav";

    @NotBlank
    private String cacheControl = "public, max-age=31536000, immutable";

    private boolean cdnRegionRoutingEnabled = false;

    private String cdnRegionDefault = "";

    private String cdnRegionTenantMap = "";

    private String cdnRegionBaseUrls = "";

    private boolean cdnOriginFallbackEnabled = true;

    @NotBlank
    private String cacheScope = "tenant";

    private boolean cacheShardEnabled = false;

    @Min(1)
    private int cacheShardPrefixLength = 2;

    private boolean cdnSigningEnabled = false;

    private String cdnSigningKey = "";

    @Min(1)
    private long cdnSigningTtlSeconds = 600L;

    @NotBlank
    private String cdnSigningExpiresParam = "expires";

    @NotBlank
    private String cdnSigningSignatureParam = "sig";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    public void setPathStyleAccess(boolean pathStyleAccess) {
        this.pathStyleAccess = pathStyleAccess;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public String getObjectSuffix() {
        return objectSuffix;
    }

    public void setObjectSuffix(String objectSuffix) {
        this.objectSuffix = objectSuffix;
    }

    public String getCacheControl() {
        return cacheControl;
    }

    public void setCacheControl(String cacheControl) {
        this.cacheControl = cacheControl;
    }

    public boolean isCdnRegionRoutingEnabled() {
        return cdnRegionRoutingEnabled;
    }

    public void setCdnRegionRoutingEnabled(boolean cdnRegionRoutingEnabled) {
        this.cdnRegionRoutingEnabled = cdnRegionRoutingEnabled;
    }

    public String getCdnRegionDefault() {
        return cdnRegionDefault;
    }

    public void setCdnRegionDefault(String cdnRegionDefault) {
        this.cdnRegionDefault = cdnRegionDefault;
    }

    public String getCdnRegionTenantMap() {
        return cdnRegionTenantMap;
    }

    public void setCdnRegionTenantMap(String cdnRegionTenantMap) {
        this.cdnRegionTenantMap = cdnRegionTenantMap;
    }

    public String getCdnRegionBaseUrls() {
        return cdnRegionBaseUrls;
    }

    public void setCdnRegionBaseUrls(String cdnRegionBaseUrls) {
        this.cdnRegionBaseUrls = cdnRegionBaseUrls;
    }

    public boolean isCdnOriginFallbackEnabled() {
        return cdnOriginFallbackEnabled;
    }

    public void setCdnOriginFallbackEnabled(boolean cdnOriginFallbackEnabled) {
        this.cdnOriginFallbackEnabled = cdnOriginFallbackEnabled;
    }

    public String getCacheScope() {
        return cacheScope;
    }

    public void setCacheScope(String cacheScope) {
        this.cacheScope = cacheScope;
    }

    public boolean isCacheShardEnabled() {
        return cacheShardEnabled;
    }

    public void setCacheShardEnabled(boolean cacheShardEnabled) {
        this.cacheShardEnabled = cacheShardEnabled;
    }

    public int getCacheShardPrefixLength() {
        return cacheShardPrefixLength;
    }

    public void setCacheShardPrefixLength(int cacheShardPrefixLength) {
        this.cacheShardPrefixLength = cacheShardPrefixLength;
    }

    public boolean isCdnSigningEnabled() {
        return cdnSigningEnabled;
    }

    public void setCdnSigningEnabled(boolean cdnSigningEnabled) {
        this.cdnSigningEnabled = cdnSigningEnabled;
    }

    public String getCdnSigningKey() {
        return cdnSigningKey;
    }

    public void setCdnSigningKey(String cdnSigningKey) {
        this.cdnSigningKey = cdnSigningKey;
    }

    public long getCdnSigningTtlSeconds() {
        return cdnSigningTtlSeconds;
    }

    public void setCdnSigningTtlSeconds(long cdnSigningTtlSeconds) {
        this.cdnSigningTtlSeconds = cdnSigningTtlSeconds;
    }

    public String getCdnSigningExpiresParam() {
        return cdnSigningExpiresParam;
    }

    public void setCdnSigningExpiresParam(String cdnSigningExpiresParam) {
        this.cdnSigningExpiresParam = cdnSigningExpiresParam;
    }

    public String getCdnSigningSignatureParam() {
        return cdnSigningSignatureParam;
    }

    public void setCdnSigningSignatureParam(String cdnSigningSignatureParam) {
        this.cdnSigningSignatureParam = cdnSigningSignatureParam;
    }
}
