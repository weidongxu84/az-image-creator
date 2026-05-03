package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.TokenCredential;
import com.azure.core.util.Configuration;
import com.azure.identity.DefaultAzureCredentialBuilder;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
public class AppConfig {

    private final String username;
    private final String personalToken;
    private final String openAIEndpoint;
    private final String openAIDeployment;
    private final String openAIApiKey;
    private final String storageAccountName;
    private final String storageContainerName;
    private final TokenCredential credential;

    public AppConfig() {
        Configuration config = Configuration.getGlobalConfiguration();

        username = Objects.requireNonNull(config.get("PERSONAL_USERNAME"), "PERSONAL_USERNAME must be set");
        personalToken = Objects.requireNonNull(config.get("PERSONAL_TOKEN"), "PERSONAL_TOKEN must be set");

        openAIEndpoint = config.get("AZURE_OPENAI_ENDPOINT",
                "https://weido-measar51-eastus2.cognitiveservices.azure.com");
        openAIDeployment = config.get("AZURE_OPENAI_DEPLOYMENT", "gpt-image-2");
        openAIApiKey = config.get("AZURE_OPENAI_IMAGE_API_KEY"); // optional: falls back to managed identity

        storageAccountName = Objects.requireNonNull(config.get("STORAGE_ACCOUNT_NAME"),
                "STORAGE_ACCOUNT_NAME must be set");
        storageContainerName = config.get("STORAGE_CONTAINER_NAME", "images");

        credential = new DefaultAzureCredentialBuilder().build();
    }

    public String getUsername() { return username; }
    public String getPersonalToken() { return personalToken; }
    public String getOpenAIEndpoint() { return openAIEndpoint; }
    public String getOpenAIDeployment() { return openAIDeployment; }
    public String getOpenAIApiKey() { return openAIApiKey; }
    public String getStorageAccountName() { return storageAccountName; }
    public String getStorageContainerName() { return storageContainerName; }
    public TokenCredential getCredential() { return credential; }
}
