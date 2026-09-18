package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.TokenCredential;
import com.azure.core.util.Configuration;
import com.azure.identity.ChainedTokenCredentialBuilder;
import com.azure.identity.EnvironmentCredentialBuilder;
import com.azure.identity.ManagedIdentityCredentialBuilder;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class AppConfig {

    private final String username;
    private final String personalToken;
    private final boolean localMode;
    private final String openAIEndpoint;
    private final String openAIDeployment;
    private final String openAIFlareDeployment;
    private final String openAISunburstDeployment;
    private final String openAIChatDeployment;
    private final String openAIValidationDeployment;
    private final String openAIApiKey;
    private final boolean useAlternateImageEndpoint;
    private final String alternateImageEndpoint;
    private final String alternateImageDeployment;
    private final String alternateImageFlareDeployment;
    private final String alternateImageSunburstDeployment;
    private final String alternateImageApiKey;
    private final String fluxEndpoint;
    private final String fluxDeployment;
    private final String storageAccountName;
    private final String storageContainerName;
    private final String storagePromptTableName;
    private final String storageConnectionString;
    private final TokenCredential credential;

    public AppConfig() {
        Configuration config = Configuration.getGlobalConfiguration();

        username = Objects.requireNonNull(config.get("PERSONAL_USERNAME"), "PERSONAL_USERNAME must be set");
        personalToken = Objects.requireNonNull(config.get("PERSONAL_TOKEN"), "PERSONAL_TOKEN must be set");
        localMode = "local".equalsIgnoreCase(config.get("APP_MODE", "cloud"));

        openAIEndpoint = Objects.requireNonNull(config.get("AZURE_OPENAI_ENDPOINT"),
                "AZURE_OPENAI_ENDPOINT must be set");
        openAIDeployment = config.get("AZURE_OPENAI_DEPLOYMENT", "gpt-image-2");
        openAIFlareDeployment = config.get("AZURE_OPENAI_FLARE_DEPLOYMENT", "gpt-image-2.5-flare");
        openAISunburstDeployment =
                config.get("AZURE_OPENAI_SUNBURST_DEPLOYMENT", "gpt-image-2.5-sunburst");
        openAIChatDeployment = config.get("AZURE_OPENAI_CHAT_DEPLOYMENT", "gpt-5.6-sol");
        openAIValidationDeployment =
                config.get("AZURE_OPENAI_VALIDATION_DEPLOYMENT", "gpt-5.4-nano");
        openAIApiKey = config.get("AZURE_OPENAI_IMAGE_API_KEY"); // optional: falls back to managed identity
        useAlternateImageEndpoint = Boolean.parseBoolean(
                config.get("AZURE_OPENAI_USE_ALTERNATE_IMAGE_ENDPOINT", "false"));
        alternateImageEndpoint = config.get("AZURE_OPENAI_ALT_IMAGE_ENDPOINT");
        alternateImageDeployment = config.get("AZURE_OPENAI_ALT_IMAGE_DEPLOYMENT", "gpt-image-2");
        alternateImageFlareDeployment =
                config.get("AZURE_OPENAI_ALT_FLARE_DEPLOYMENT", "gpt-image-2.5-flare");
        alternateImageSunburstDeployment =
                config.get("AZURE_OPENAI_ALT_SUNBURST_DEPLOYMENT", "gpt-image-2.5-sunburst");
        alternateImageApiKey = config.get("AZURE_OPENAI_ALT_IMAGE_API_KEY");
        if (useAlternateImageEndpoint) {
            requireNonBlank(alternateImageEndpoint, "AZURE_OPENAI_ALT_IMAGE_ENDPOINT must be set");
            requireNonBlank(alternateImageApiKey, "AZURE_OPENAI_ALT_IMAGE_API_KEY must be set");
        } else if (localMode) {
            requireNonBlank(openAIApiKey, "AZURE_OPENAI_IMAGE_API_KEY must be set in local mode");
        }

        fluxEndpoint = config.get("AZURE_FLUX_ENDPOINT"); // optional: FLUX.2 model endpoint
        fluxDeployment = config.get("AZURE_FLUX_DEPLOYMENT", "FLUX.2-pro");

        storageAccountName = Objects.requireNonNull(config.get("STORAGE_ACCOUNT_NAME"),
                "STORAGE_ACCOUNT_NAME must be set");
        storageContainerName = config.get("STORAGE_CONTAINER_NAME", "images");
        storagePromptTableName = config.get("STORAGE_PROMPT_TABLE_NAME", "imageprompts");
        storageConnectionString = config.get("AZURE_STORAGE_CONNECTION_STRING");
        if (localMode) {
            requireNonBlank(storageConnectionString,
                    "AZURE_STORAGE_CONNECTION_STRING must be set in local mode");
        }

        credential = localMode ? null : new ChainedTokenCredentialBuilder()
                .addLast(new EnvironmentCredentialBuilder().build())
                .addLast(new ManagedIdentityCredentialBuilder().build())
                .build();
    }

    public String getUsername() { return username; }
    public String getPersonalToken() { return personalToken; }
    public boolean isLocalMode() { return localMode; }
    public String getOpenAIEndpoint() { return openAIEndpoint; }
    public String getOpenAIDeployment() { return openAIDeployment; }
    public String getOpenAIFlareDeployment() { return openAIFlareDeployment; }
    public String getOpenAISunburstDeployment() { return openAISunburstDeployment; }
    public String getOpenAIChatDeployment() { return openAIChatDeployment; }
    public String getOpenAIValidationDeployment() { return openAIValidationDeployment; }
    public String getOpenAIApiKey() { return openAIApiKey; }
    public boolean isUseAlternateImageEndpoint() { return useAlternateImageEndpoint; }
    public String getAlternateImageEndpoint() { return alternateImageEndpoint; }
    public String getAlternateImageDeployment() { return alternateImageDeployment; }
    public String getAlternateImageFlareDeployment() { return alternateImageFlareDeployment; }
    public String getAlternateImageSunburstDeployment() { return alternateImageSunburstDeployment; }
    public String getAlternateImageApiKey() { return alternateImageApiKey; }
    public String getFluxEndpoint() { return fluxEndpoint; }
    public String getFluxDeployment() { return fluxDeployment; }
    public String getStorageAccountName() { return storageAccountName; }
    public String getStorageContainerName() { return storageContainerName; }
    public String getStoragePromptTableName() { return storagePromptTableName; }
    public String getStorageConnectionString() { return storageConnectionString; }
    public boolean hasStorageConnectionString() {
        return storageConnectionString != null && !storageConnectionString.isBlank();
    }
    public TokenCredential getCredential() { return credential; }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
