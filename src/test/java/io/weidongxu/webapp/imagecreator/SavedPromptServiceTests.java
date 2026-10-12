package io.weidongxu.webapp.imagecreator;

import com.azure.core.http.HttpHeaders;
import com.azure.core.http.rest.PagedIterable;
import com.azure.core.http.rest.SimpleResponse;
import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.models.BlobDownloadContentResponse;
import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobRequestConditions;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.BlockBlobItem;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SavedPromptServiceTests {

    private static final String VERSION = "\"v1\"";
    private static final String NEXT_VERSION = "\"v2\"";
    private final BlobContainerClient container = mock(BlobContainerClient.class);
    private final BlobClient blob = mock(BlobClient.class);
    private final SavedPromptService service = new SavedPromptService(container);

    @BeforeEach
    void setUp() {
        when(container.getBlobClient(anyString())).thenReturn(blob);
        stubUpload(blob, NEXT_VERSION);
    }

    private void stubUpload(BlobClient client, String version) {
        BlockBlobItem result = mock(BlockBlobItem.class);
        when(result.getETag()).thenReturn(version);
        when(client.uploadWithResponse(any(BlobParallelUploadOptions.class), isNull(), isNull()))
                .thenReturn(new SimpleResponse<>(null, 201, new HttpHeaders(), result));
    }

    private void stubRead(BlobClient client, SavedPrompt saved) {
        stubRead(client, saved, VERSION);
    }

    private void stubRead(BlobClient client, SavedPrompt saved, String etag) {
        BlobDownloadContentResponse response = mock(BlobDownloadContentResponse.class);
        when(response.getValue()).thenReturn(BinaryData.fromObject(saved));
        when(response.getHeaders()).thenReturn(new HttpHeaders().set("ETag", etag));
        when(client.downloadContentWithResponse(isNull(), any(), isNull(), isNull())).thenReturn(response);
    }

    private BlobStorageException storageException(int status) {
        BlobStorageException exception = mock(BlobStorageException.class);
        when(exception.getStatusCode()).thenReturn(status);
        return exception;
    }

    private SavedPrompt uploaded(BlobParallelUploadOptions options) {
        return BinaryData.fromString(StandardCharsets.UTF_8.decode(options.getDataFlux().blockFirst()).toString())
                .toObject(SavedPrompt.class);
    }

    @Test
    void savesExactTextAndInternalSpacesWithAtomicNoOverwriteCondition() {
        SavedPromptDetails saved = service.save(new SavedPrompt("  Night  sky  ", "  A scene\nwith \"trees\".  "));
        ArgumentCaptor<BlobParallelUploadOptions> options = ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(blob).uploadWithResponse(options.capture(), isNull(), isNull());
        assertThat(saved).isEqualTo(new SavedPromptDetails("Night  sky", "  A scene\nwith \"trees\".  ", NEXT_VERSION));
        assertThat(uploaded(options.getValue())).isEqualTo(new SavedPrompt(saved.name(), saved.prompt()));
        assertThat(options.getValue().getHeaders().getContentType()).isEqualTo("application/json");
        assertThat(options.getValue().getRequestConditions().getIfNoneMatch()).isEqualTo("*");
        verify(container).createIfNotExists();
    }

    @Test
    void usesSameSafeKeyForTrimmedCaseInsensitiveAndNormalizedNames() {
        service.save(new SavedPrompt("  Caf\u00e9  sky  ", "one"));
        service.save(new SavedPrompt("CAFE\u0301  sky", "two"));
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(container, times(2)).getBlobClient(names.capture());
        assertThat(names.getAllValues()).allMatch(name -> name.equals(names.getValue()));
        assertThat(names.getValue()).matches("[A-Za-z0-9_-]+\\.json");
    }

    @Test
    void rejectsInvalidInputBeforeAccessingStorage() {
        for (String name : new String[] { null, "", "  ", "a".repeat(101), "a\nb" }) {
            assertThatThrownBy(() -> service.save(new SavedPrompt(name, "text")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.delete(name, VERSION)).isInstanceOf(IllegalArgumentException.class);
        }
        for (char character : "/\\:*?\"<>|".toCharArray()) {
            assertThatThrownBy(() -> service.save(new SavedPrompt("a" + character + "b", "text")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.rename(new SavedPromptRenameRequest("old", "a" + character), VERSION))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String prompt : new String[] { null, "", "  ", "a".repeat(100_001) }) {
            assertThatThrownBy(() -> service.save(new SavedPrompt("name", prompt)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.update(new SavedPrompt("name", prompt), VERSION))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.save(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update(null, VERSION)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.rename(null, VERSION)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(container);
    }

    @Test
    void rejectsMissingWildcardAndMultipleEtags() {
        for (String version : new String[] { null, "", "*", "v1", "\"v1\", \"v2\"", "\"bad\nversion\"" }) {
            assertThatThrownBy(() -> service.update(new SavedPrompt("name", "text"), version))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.rename(new SavedPromptRenameRequest("name", "new"), version))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.delete("name", version)).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(container);
    }

    @Test
    void acceptsNameAndPromptAtLengthLimits() {
        SavedPrompt request = new SavedPrompt("a".repeat(100), "p".repeat(100_000));
        SavedPromptDetails saved = service.save(request);
        assertThat(saved.name()).isEqualTo(request.name());
        assertThat(saved.prompt()).isEqualTo(request.prompt());
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
    void listsLegacyJsonWithEtagsSortedByName() {
        PagedIterable<BlobItem> items = mock(PagedIterable.class);
        when(container.listBlobs()).thenReturn(items);
        when(items.stream()).thenAnswer(invocation -> List.of(
                new BlobItem().setName("z.json"), new BlobItem().setName("a.json"),
                new BlobItem().setName("other.txt")).stream());
        BlobClient apple = mock(BlobClient.class);
        when(container.getBlobClient("a.json")).thenReturn(apple);
        stubRead(blob, new SavedPrompt("Zoo / legacy", "animals"), "v1");
        stubRead(apple, new SavedPrompt("apple", "fruit"));
        assertThat(service.list()).containsExactly(
                new SavedPromptDetails("apple", "fruit", VERSION),
                new SavedPromptDetails("Zoo / legacy", "animals", VERSION));
        verify(container).createIfNotExists();
    }

    @Test
    void updatesLegacyNamesWithConditionalReadAndWriteWithoutChangingDisplayName() {
        stubRead(blob, new SavedPrompt("Name / Legacy", "original"));
        assertThat(service.update(new SavedPrompt("name / legacy", "  edited  "), VERSION))
                .isEqualTo(new SavedPromptDetails("Name / Legacy", "  edited  ", NEXT_VERSION));
        ArgumentCaptor<BlobRequestConditions> read = ArgumentCaptor.forClass(BlobRequestConditions.class);
        verify(blob).downloadContentWithResponse(isNull(), read.capture(), isNull(), isNull());
        ArgumentCaptor<BlobParallelUploadOptions> write = ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(blob).uploadWithResponse(write.capture(), isNull(), isNull());
        assertThat(read.getValue().getIfMatch()).isEqualTo(VERSION);
        assertThat(write.getValue().getRequestConditions().getIfMatch()).isEqualTo(VERSION);
    }

    @Test
    void deletesLegacyNamesConditionallyAndReportsMissingPrompts() {
        when(blob.deleteIfExistsWithResponse(isNull(), any(), isNull(), isNull()))
                .thenReturn(new SimpleResponse<>(null, 202, new HttpHeaders(), true),
                        new SimpleResponse<>(null, 404, new HttpHeaders(), false));
        assertThat(service.delete("name / legacy", VERSION)).isTrue();
        assertThat(service.delete("name / legacy", VERSION)).isFalse();
        ArgumentCaptor<BlobRequestConditions> conditions = ArgumentCaptor.forClass(BlobRequestConditions.class);
        verify(blob, times(2)).deleteIfExistsWithResponse(isNull(), conditions.capture(), isNull(), isNull());
        assertThat(conditions.getAllValues()).allMatch(condition -> VERSION.equals(condition.getIfMatch()));
    }

    @Test
    void caseOnlyRenameUpdatesSameBlobConditionally() {
        stubRead(blob, new SavedPrompt("night  sky", "text"));
        assertThat(service.rename(new SavedPromptRenameRequest("night  sky", "  Night  Sky  "), VERSION))
                .isEqualTo(new SavedPromptDetails("Night  Sky", "text", NEXT_VERSION));
        verify(container, times(1)).getBlobClient(anyString());
        verify(blob, never()).deleteWithResponse(any(), any(), any(), any());
    }

    private BlobClient renameDestination() {
        BlobClient destination = mock(BlobClient.class);
        when(container.getBlobClient(anyString())).thenReturn(blob, destination);
        stubRead(blob, new SavedPrompt("old / name", "  exact text  "));
        stubUpload(destination, "v2");
        return destination;
    }

    @Test
    void renameCreatesDestinationWithoutOverwriteBeforeConditionalSourceDeletion() {
        BlobClient destination = renameDestination();
        assertThat(service.rename(new SavedPromptRenameRequest("old / name", "new name"), VERSION))
                .isEqualTo(new SavedPromptDetails("new name", "  exact text  ", NEXT_VERSION));
        ArgumentCaptor<BlobParallelUploadOptions> options = ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(destination).uploadWithResponse(options.capture(), isNull(), isNull());
        assertThat(uploaded(options.getValue())).isEqualTo(new SavedPrompt("new name", "  exact text  "));
        assertThat(options.getValue().getRequestConditions().getIfNoneMatch()).isEqualTo("*");
        ArgumentCaptor<BlobRequestConditions> conditions = ArgumentCaptor.forClass(BlobRequestConditions.class);
        verify(blob).deleteWithResponse(isNull(), conditions.capture(), isNull(), isNull());
        assertThat(conditions.getValue().getIfMatch()).isEqualTo(VERSION);
    }

    @Test
    void conflictingDestinationLeavesOriginalUntouched() {
        BlobClient destination = renameDestination();
        BlobStorageException conflict = storageException(409);
        when(conflict.getErrorCode()).thenReturn(BlobErrorCode.BLOB_ALREADY_EXISTS);
        doThrow(conflict).when(destination).uploadWithResponse(any(BlobParallelUploadOptions.class), isNull(), isNull());
        assertThatThrownBy(() -> service.rename(new SavedPromptRenameRequest("old / name", "new"), VERSION))
                .isInstanceOf(SavedPromptNameConflictException.class).hasCause(conflict);
        verify(blob, never()).deleteWithResponse(any(), any(), any(), any());
    }

    @Test
    void sourceChangedDuringRenameRollsBackOnlyUnchangedDestination() {
        BlobClient destination = renameDestination();
        BlobStorageException conflict = storageException(412);
        doThrow(conflict).when(blob).deleteWithResponse(isNull(), any(), isNull(), isNull());
        assertThatThrownBy(() -> service.rename(new SavedPromptRenameRequest("old / name", "new"), VERSION))
                .isSameAs(conflict);
        ArgumentCaptor<BlobRequestConditions> conditions = ArgumentCaptor.forClass(BlobRequestConditions.class);
        verify(destination).deleteWithResponse(isNull(), conditions.capture(), isNull(), isNull());
        assertThat(conditions.getValue().getIfMatch()).isEqualTo(NEXT_VERSION);
    }

    @Test
    void rollbackFailureIsExplicitAndDoesNotMaskOriginalConflict() {
        BlobClient destination = renameDestination();
        BlobStorageException conflict = storageException(412);
        BlobStorageException cleanup = storageException(412);
        doThrow(conflict).when(blob).deleteWithResponse(isNull(), any(), isNull(), isNull());
        doThrow(cleanup).when(destination).deleteWithResponse(isNull(), any(), isNull(), isNull());
        assertThatThrownBy(() -> service.rename(new SavedPromptRenameRequest("old / name", "new"), VERSION))
                .isInstanceOf(SavedPromptRenameException.class).hasCause(conflict);
        verify(destination).deleteWithResponse(isNull(), any(), isNull(), isNull());
    }

    @Test
    void uncertainSourceDeleteDoesNotRollBackAndRiskLosingBothCopies() {
        BlobClient destination = renameDestination();
        doThrow(new IllegalStateException("timeout")).when(blob)
                .deleteWithResponse(isNull(), any(), isNull(), isNull());
        assertThatThrownBy(() -> service.rename(new SavedPromptRenameRequest("old / name", "new"), VERSION))
                .isInstanceOf(SavedPromptRenameException.class).hasMessageContaining("Both names may be present");
        verify(destination, never()).deleteWithResponse(any(), any(), any(), any());
    }

    @Test
    void staleReadDoesNotWriteAnyChanges() {
        BlobStorageException conflict = storageException(412);
        doThrow(conflict).when(blob).downloadContentWithResponse(isNull(), any(), isNull(), isNull());
        assertThatThrownBy(() -> service.update(new SavedPrompt("name", "text"), VERSION)).isSameAs(conflict);
        assertThatThrownBy(() -> service.rename(new SavedPromptRenameRequest("name", "new"), VERSION))
                .isSameAs(conflict);
        verify(blob, never()).uploadWithResponse(any(BlobParallelUploadOptions.class), any(), any());
    }

    @Test
    void updateRejectsAnEditThatArrivesAfterTheConditionalRead() {
        stubRead(blob, new SavedPrompt("name", "original"));
        BlobStorageException conflict = storageException(412);
        doThrow(conflict).when(blob).uploadWithResponse(any(BlobParallelUploadOptions.class), isNull(), isNull());
        assertThatThrownBy(() -> service.update(new SavedPrompt("name", "my edits"), VERSION)).isSameAs(conflict);
        ArgumentCaptor<BlobParallelUploadOptions> options = ArgumentCaptor.forClass(BlobParallelUploadOptions.class);
        verify(blob).uploadWithResponse(options.capture(), isNull(), isNull());
        assertThat(options.getValue().getRequestConditions().getIfMatch()).isEqualTo(VERSION);
    }

    @Test
    void conditionalCreateMapsBothAzureNameConflictCodesToNameConflict() {
        for (BlobErrorCode code : List.of(BlobErrorCode.BLOB_ALREADY_EXISTS, BlobErrorCode.CONDITION_NOT_MET)) {
            BlobStorageException conflict = storageException(412);
            when(conflict.getErrorCode()).thenReturn(code);
            doThrow(conflict).when(blob).uploadWithResponse(any(BlobParallelUploadOptions.class), isNull(), isNull());
            assertThatThrownBy(() -> service.save(new SavedPrompt("name", "text")))
                    .isInstanceOf(SavedPromptNameConflictException.class).hasCause(conflict);
        }
    }

    @Test
    void quotesUploadEtagsSoClientsCanReuseThemInIfMatch() {
        stubUpload(blob, "0x1234");
        SavedPromptDetails saved = service.save(new SavedPrompt("name", "original"));
        assertThat(saved.etag()).isEqualTo("\"0x1234\"");
        stubRead(blob, new SavedPrompt("name", "original"), "0x1234");
        stubUpload(blob, "0x5678");
        assertThat(service.update(new SavedPrompt("name", "edited"), saved.etag()).etag())
                .isEqualTo("\"0x5678\"");
    }
}
