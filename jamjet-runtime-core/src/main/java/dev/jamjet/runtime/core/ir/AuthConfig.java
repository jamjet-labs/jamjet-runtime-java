package dev.jamjet.runtime.core.ir;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AuthConfig.Bearer.class, name = "bearer"),
        @JsonSubTypes.Type(value = AuthConfig.ApiKey.class, name = "api_key"),
        @JsonSubTypes.Type(value = AuthConfig.Oauth2.class, name = "oauth2")
})
public sealed interface AuthConfig {
    record Bearer(String tokenEnv) implements AuthConfig {}
    record ApiKey(String header, String keyEnv) implements AuthConfig {}
    record Oauth2(String clientIdEnv, String clientSecretEnv, String tokenUrl) implements AuthConfig {}
}
