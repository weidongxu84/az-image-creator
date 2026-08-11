package io.weidongxu.webapp.imagecreator;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.TableClientBuilder;
import com.azure.data.tables.models.TableEntity;
import com.azure.data.tables.models.TableServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

@Service
public class PromptStorageService {

    private final TableClient tableClient;

    // Explicitly marked: PromptStorageService has a second (package-private, test-only)
    // constructor below, which makes Spring's constructor autowiring ambiguous without
    // this annotation — it was intermittently failing with "No default constructor found".
    @Autowired
    public PromptStorageService(AppConfig config) {
        this(new TableClientBuilder()
                .endpoint("https://" + config.getStorageAccountName() + ".table.core.windows.net")
                .credential(config.getCredential())
                .tableName(config.getStoragePromptTableName())
                .buildClient());
    }

    PromptStorageService(TableClient tableClient) {
        this.tableClient = tableClient;
    }

    public void save(ImagePrompt prompt) {
        OffsetDateTime createdAt = prompt.createdAt().withOffsetSameInstant(ZoneOffset.UTC);
        TableEntity entity = new TableEntity(partitionKey(prompt.blobName()), rowKey(prompt.blobName()))
                .addProperty("BlobName", prompt.blobName())
                .addProperty("Prompt", prompt.prompt())
                .addProperty("CreatedAt", createdAt)
                .addProperty("Model", prompt.model())
                .addProperty("Provider", prompt.provider())
                .addProperty("OutputFormat", prompt.outputFormat())
                .addProperty("Operation", prompt.operation())
                .addProperty("JobId", prompt.jobId())
                .addProperty("ReferenceImageCount", prompt.referenceImageCount());
        tableClient.createEntity(entity);
    }

    public Map<String, String> listPrompts() {
        Map<String, String> prompts = new HashMap<>();
        tableClient.listEntities().forEach(entity -> {
            Object blobName = entity.getProperty("BlobName");
            Object prompt = entity.getProperty("Prompt");
            if (blobName instanceof String name && prompt instanceof String value) {
                prompts.put(name, value);
            }
        });
        return prompts;
    }

    public boolean delete(String blobName) {
        try {
            tableClient.deleteEntity(partitionKey(blobName), rowKey(blobName));
            return true;
        } catch (TableServiceException e) {
            if (e.getResponse() != null && e.getResponse().getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    static String partitionKey(String blobName) {
        String[] parts = blobName.split("/", 3);
        if (parts.length < 2 || parts[0].length() != 4 || parts[1].length() != 2) {
            throw new IllegalArgumentException("Blob name does not contain a yyyy/MM prefix: " + blobName);
        }
        return parts[0] + "-" + parts[1];
    }

    static String rowKey(String blobName) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(blobName.getBytes(StandardCharsets.UTF_8));
    }
}
