package io.weidongxu.webapp.imagecreator;

import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SavedPromptControllerTests {

    private final SavedPromptService service = mock(SavedPromptService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SavedPromptController(service)).build();

    @Test
    void savesAndListsPromptJson() throws Exception {
        SavedPrompt saved = new SavedPrompt("Landscape", "A scene");
        when(service.save(saved)).thenReturn(saved);
        when(service.list()).thenReturn(List.of(saved));
        mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Landscape\",\"prompt\":\"A scene\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Landscape"));
        mvc.perform(get("/api/saved-prompts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].prompt").value("A scene"));
        verify(service).save(saved);
    }

    @Test
    void returnsEmptyListWhenNoPromptsAreSaved() throws Exception {
        when(service.list()).thenReturn(List.of());
        mvc.perform(get("/api/saved-prompts")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void rejectsDuplicateNameForBothAzureConflictResponses() throws Exception {
        for (BlobErrorCode code : List.of(BlobErrorCode.BLOB_ALREADY_EXISTS, BlobErrorCode.CONDITION_NOT_MET)) {
            BlobStorageException exception = mock(BlobStorageException.class);
            when(exception.getErrorCode()).thenReturn(code);
            doThrow(exception).when(service).save(any());
            mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"name\",\"prompt\":\"text\"}"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value(
                            "That name already exists. Choose another name."));
        }
    }

    @Test
    void returnsValidationErrorsAndRejectsMalformedJson() throws Exception {
        when(service.save(any())).thenThrow(new IllegalArgumentException("Name is required."));
        mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"prompt\":\"text\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Name is required."));
        mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletesByQueryParameterAndReportsMissingNames() throws Exception {
        when(service.delete("name / &")).thenReturn(true);
        mvc.perform(delete("/api/saved-prompts").param("name", "name / &"))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/saved-prompts").param("name", "missing"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/saved-prompts")).andExpect(status().isBadRequest());
        when(service.delete(" ")).thenThrow(new IllegalArgumentException("Name is required."));
        mvc.perform(delete("/api/saved-prompts").param("name", " "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
    }

    @Test
    void reportsStorageOutageForEveryOperation() throws Exception {
        BlobStorageException exception = mock(BlobStorageException.class);
        when(exception.getErrorCode()).thenReturn(BlobErrorCode.AUTHORIZATION_FAILURE);
        when(service.save(any())).thenThrow(exception);
        when(service.list()).thenThrow(exception);
        when(service.delete("name")).thenThrow(exception);
        mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"name\",\"prompt\":\"text\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/saved-prompts")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(delete("/api/saved-prompts").param("name", "name"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").exists());
    }
}
