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
    private final String openAIEndpoint;
    private final String openAIDeployment;
    private final String openAIChatDeployment;
    private final String openAIApiKey;
    private final boolean useAlternateImageEndpoint;
    private final String alternateImageEndpoint;
    private final String alternateImageDeployment;
    private final String alternateImageApiKey;
    private final String fluxEndpoint;
    private final String fluxDeployment;
    private final String storageAccountName;
    private final String storageContainerName;
    private final String storagePromptTableName;
    private final TokenCredential credential;

    public AppConfig() {
        Configuration config = Configuration.getGlobalConfiguration();

        username = Objects.requireNonNull(config.get("PERSONAL_USERNAME"), "PERSONAL_USERNAME must be set");
        personalToken = Objects.requireNonNull(config.get("PERSONAL_TOKEN"), "PERSONAL_TOKEN must be set");

        openAIEndpoint = Objects.requireNonNull(config.get("AZURE_OPENAI_ENDPOINT"),
                "AZURE_OPENAI_ENDPOINT must be set");
        openAIDeployment = config.get("AZURE_OPENAI_DEPLOYMENT", "gpt-image-2");
        openAIChatDeployment = config.get("AZURE_OPENAI_CHAT_DEPLOYMENT", "gpt-5.4");
        openAIApiKey = config.get("AZURE_OPENAI_IMAGE_API_KEY"); // optional: falls back to managed identity
        useAlternateImageEndpoint = Boolean.parseBoolean(
                config.get("AZURE_OPENAI_USE_ALTERNATE_IMAGE_ENDPOINT", "false"));
        alternateImageEndpoint = config.get("AZURE_OPENAI_ALT_IMAGE_ENDPOINT");
        alternateImageDeployment = config.get("AZURE_OPENAI_ALT_IMAGE_DEPLOYMENT", "gpt-image-2");
        alternateImageApiKey = config.get("AZURE_OPENAI_ALT_IMAGE_API_KEY");
        if (useAlternateImageEndpoint) {
            requireNonBlank(alternateImageEndpoint, "AZURE_OPENAI_ALT_IMAGE_ENDPOINT must be set");
            requireNonBlank(alternateImageApiKey, "AZURE_OPENAI_ALT_IMAGE_API_KEY must be set");
        }

        fluxEndpoint = config.get("AZURE_FLUX_ENDPOINT"); // optional: FLUX.2 model endpoint
        fluxDeployment = config.get("AZURE_FLUX_DEPLOYMENT", "FLUX.2-pro");

        storageAccountName = Objects.requireNonNull(config.get("STORAGE_ACCOUNT_NAME"),
                "STORAGE_ACCOUNT_NAME must be set");
        storageContainerName = config.get("STORAGE_CONTAINER_NAME", "images");
        storagePromptTableName = config.get("STORAGE_PROMPT_TABLE_NAME", "imageprompts");

        credential = new ChainedTokenCredentialBuilder()
                .addLast(new EnvironmentCredentialBuilder().build())
                .addLast(new ManagedIdentityCredentialBuilder().build())
                .build();
    }

    public String getUsername() { return username; }
    public String getPersonalToken() { return personalToken; }
    public String getOpenAIEndpoint() { return openAIEndpoint; }
    public String getOpenAIDeployment() { return openAIDeployment; }
    public String getOpenAIChatDeployment() { return openAIChatDeployment; }
    public String getOpenAIApiKey() { return openAIApiKey; }
    public boolean isUseAlternateImageEndpoint() { return useAlternateImageEndpoint; }
    public String getAlternateImageEndpoint() { return alternateImageEndpoint; }
    public String getAlternateImageDeployment() { return alternateImageDeployment; }
    public String getAlternateImageApiKey() { return alternateImageApiKey; }
    public String getFluxEndpoint() { return fluxEndpoint; }
    public String getFluxDeployment() { return fluxDeployment; }
    public String getStorageAccountName() { return storageAccountName; }
    public String getStorageContainerName() { return storageContainerName; }
    public String getStoragePromptTableName() { return storagePromptTableName; }
    public TokenCredential getCredential() { return credential; }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
