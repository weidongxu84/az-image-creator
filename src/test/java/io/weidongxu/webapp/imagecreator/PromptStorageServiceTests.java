package io.weidongxu.webapp.imagecreator;

import com.azure.data.tables.TableClient;
import com.azure.data.tables.models.TableEntity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PromptStorageServiceTests {

    @Test
    void savesAllImageProperties() {
        TableClient tableClient = mock(TableClient.class);
        PromptStorageService service = new PromptStorageService(tableClient);
        OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-31T03:00:00Z");

        service.save(new ImagePrompt(
                "2026/07/31/image.png", "a long prompt", createdAt, "gpt-image-2",
                "azure-openai", "png", "edit", "job-1", 2));

        ArgumentCaptor<TableEntity> captor = ArgumentCaptor.forClass(TableEntity.class);
        verify(tableClient).createEntity(captor.capture());
        TableEntity entity = captor.getValue();
        assertThat(entity.getPartitionKey()).isEqualTo("2026-07");
        assertThat(entity.getRowKey()).isEqualTo(Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2026/07/31/image.png".getBytes(StandardCharsets.UTF_8)));
        assertThat(entity.getProperties()).containsEntry("BlobName", "2026/07/31/image.png")
                .containsEntry("Prompt", "a long prompt")
                .containsEntry("CreatedAt", createdAt)
                .containsEntry("Model", "gpt-image-2")
                .containsEntry("Provider", "azure-openai")
                .containsEntry("OutputFormat", "png")
                .containsEntry("Operation", "edit")
                .containsEntry("JobId", "job-1")
                .containsEntry("ReferenceImageCount", 2);
    }

    @Test
    void derivesPartitionFromBlobNameForDeletion() {
        assertThat(PromptStorageService.partitionKey("2026/07/31/image.png")).isEqualTo("2026-07");
    }

    @Test
    void derivesPartitionFromMonthlyPrefix() {
        assertThat(PromptStorageService.partitionKeyFromPrefix("2026/07/")).isEqualTo("2026-07");
        assertThat(PromptStorageService.partitionKeyFromPrefix("")).isNull();
        assertThat(PromptStorageService.partitionKeyFromPrefix(null)).isNull();
    }
}
