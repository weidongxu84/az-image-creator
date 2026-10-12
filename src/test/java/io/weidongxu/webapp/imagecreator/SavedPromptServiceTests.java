package io.weidongxu.webapp.imagecreator;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SavedPromptServiceTests {

    private final BlobContainerClient container = mock(BlobContainerClient.class);
    private final BlobClient blob = mock(BlobClient.class);
    private final SavedPromptService service = new SavedPromptService(container);

    @Test
    void savesExactPromptAsJsonWithAtomicNoOverwriteCondition() {
        when(container.getBlobClient(anyString())).thenReturn(blob);
        SavedPrompt saved = service.save(new SavedPrompt("  Landscape  ", "  A scene\nwith \"trees\".  "));

        ArgumentCaptor<BlobParallelUploadOptions> options = ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(blob).uploadWithResponse(options.capture(), isNull(), isNull());
        assertThat(saved.name()).isEqualTo("Landscape");
        assertThat(BinaryData.fromString(StandardCharsets.UTF_8.decode(
                options.getValue().getDataFlux().blockFirst()).toString()).toObject(SavedPrompt.class))
                .isEqualTo(saved);
        assertThat(options.getValue().getHeaders().getContentType()).isEqualTo("application/json");
        assertThat(options.getValue().getRequestConditions().getIfNoneMatch()).isEqualTo("*");
        verify(container).createIfNotExists();
    }

    @Test
    void usesSameSafeBlobKeyForTrimmedCaseInsensitiveAndNormalizedNames() {
        when(container.getBlobClient(anyString())).thenReturn(blob);
        service.save(new SavedPrompt("  Caf\u00e9 / ../  ", "one"));
        service.save(new SavedPrompt("CAFE\u0301 / ../", "two"));
        service.delete("caf\u00e9 / ../");

        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(container, org.mockito.Mockito.times(3)).getBlobClient(names.capture());
        assertThat(names.getAllValues()).allMatch(name -> name.equals(names.getValue()));
        assertThat(names.getValue()).matches("[A-Za-z0-9_-]+\\.json");
    }

    @Test
    void rejectsInvalidInputBeforeAccessingStorage() {
        for (String name : new String[] { null, "", "  ", "a".repeat(101), "a\nb" }) {
            assertThatThrownBy(() -> service.save(new SavedPrompt(name, "text")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.delete(name))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String prompt : new String[] { null, "", "  ", "a".repeat(100_001) }) {
            assertThatThrownBy(() -> service.save(new SavedPrompt("name", prompt)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.save(null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(container);
    }

    @Test
    void acceptsNameAndPromptAtLengthLimits() {
        when(container.getBlobClient(anyString())).thenReturn(blob);
        SavedPrompt request = new SavedPrompt("a".repeat(100), "p".repeat(100_000));
        assertThat(service.save(request)).isEqualTo(request);
    }

    @Test
    void refusesImageContainerConfiguration() {
        AppConfig config = mock(AppConfig.class);
        when(config.getStorageSavedPromptContainerName()).thenReturn("images");
        when(config.getStorageContainerName()).thenReturn("images");
        assertThatThrownBy(() -> new SavedPromptService(config))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("separate container");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listsSavedJsonPromptsSortedByName() {
        PagedIterable<BlobItem> items = mock(PagedIterable.class);
        when(container.listBlobs()).thenReturn(items);
        when(items.stream()).thenAnswer(invocation -> List.of(
                new BlobItem().setName("z.json"), new BlobItem().setName("a.json"),
                new BlobItem().setName("other.txt")).stream());
        when(container.getBlobClient(anyString())).thenReturn(blob);
        SavedPrompt z = new SavedPrompt("Zoo", "animals");
        SavedPrompt a = new SavedPrompt("apple", "fruit");
        when(blob.downloadContent()).thenReturn(BinaryData.fromObject(z), BinaryData.fromObject(a));

        assertThat(service.list()).containsExactly(a, z);
        verify(container).createIfNotExists();
    }

    @Test
    void reportsMissingAndDeletedPrompts() {
        when(container.getBlobClient(anyString())).thenReturn(blob);
        when(blob.deleteIfExists()).thenReturn(true, false);
        assertThat(service.delete("name")).isTrue();
        assertThat(service.delete("name")).isFalse();
    }

    @Test
    void propagatesStorageFailuresInsteadOfReportingSuccess() {
        when(container.getBlobClient(anyString())).thenReturn(blob);
        when(blob.uploadWithResponse(any(BlobParallelUploadOptions.class), isNull(), isNull()))
                .thenThrow(new IllegalStateException("storage failure"));
        assertThatThrownBy(() -> service.save(new SavedPrompt("name", "prompt")))
                .hasMessage("storage failure");
    }
}
