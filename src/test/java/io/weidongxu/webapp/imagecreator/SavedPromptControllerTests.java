package io.weidongxu.webapp.imagecreator;

import com.azure.storage.blob.models.BlobErrorCode;
import com.azure.storage.blob.models.BlobStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SavedPromptControllerTests {

    private final SavedPromptService service = mock(SavedPromptService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new SavedPromptController(service)).build();

    @Test
    void savesAndListsPromptJson() throws Exception {
        SavedPrompt saved = new SavedPrompt("Landscape", "A scene");
        SavedPromptDetails details = new SavedPromptDetails(saved.name(), saved.prompt(), "\"v1\"");
        when(service.save(saved)).thenReturn(details);
        when(service.list()).thenReturn(List.of(details));
        mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Landscape\",\"prompt\":\"A scene\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Landscape"))
                .andExpect(jsonPath("$.etag").value("\"v1\"")).andExpect(header().string("ETag", "\"v1\""))
                .andExpect(jsonPath("$.version").doesNotExist());
        mvc.perform(get("/api/saved-prompts"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].prompt").value("A scene"))
                .andExpect(jsonPath("$[0].etag").value("\"v1\""));
        verify(service).save(saved);
    }

    @Test
    void returnsEmptyListWhenNoPromptsAreSaved() throws Exception {
        when(service.list()).thenReturn(List.of());
        mvc.perform(get("/api/saved-prompts")).andExpect(status().isOk()).andExpect(content().json("[]"));
    }

    @Test
    void rejectsDuplicateName() throws Exception {
        doThrow(new SavedPromptNameConflictException(new IllegalStateException("name conflict")))
                .when(service).save(any());
        mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"name\",\"prompt\":\"text\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value(
                        "That name already exists. Choose another name."));
    }

    @Test
    void returnsValidationErrorsAndRejectsMalformedJson() throws Exception {
        when(service.save(any())).thenThrow(new IllegalArgumentException("Name is required."));
        mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"prompt\":\"text\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("Name is required."));
        mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
    }

    @Test
    void deletesByQueryParameterAndReportsMissingNames() throws Exception {
        when(service.delete("name / &", "\"v1\"")).thenReturn(true);
        mvc.perform(delete("/api/saved-prompts").param("name", "name / &").header("If-Match", "\"v1\""))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/saved-prompts").param("name", "missing").header("If-Match", "\"v1\""))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/saved-prompts")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        when(service.delete(" ", "\"v1\"")).thenThrow(new IllegalArgumentException("Name is required."));
        mvc.perform(delete("/api/saved-prompts").param("name", " ").header("If-Match", "\"v1\""))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").exists());
    }

    @Test
    void reportsStorageOutageForEveryOperation() throws Exception {
        BlobStorageException exception = mock(BlobStorageException.class);
        when(exception.getErrorCode()).thenReturn(BlobErrorCode.AUTHORIZATION_FAILURE);
        when(service.save(any())).thenThrow(exception);
        when(service.list()).thenThrow(exception);
        when(service.delete("name", "\"v1\"")).thenThrow(exception);
        when(service.update(any(), anyString())).thenThrow(exception);
        when(service.rename(any(), anyString())).thenThrow(exception);
        mvc.perform(post("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"name\",\"prompt\":\"text\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/saved-prompts")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(delete("/api/saved-prompts").param("name", "name").header("If-Match", "\"v1\""))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").exists());
        mvc.perform(put("/api/saved-prompts").header("If-Match", "\"v1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"name\",\"prompt\":\"text\"}"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/saved-prompts/rename").header("If-Match", "\"v1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"name\",\"newName\":\"new\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void updatesAndRenamesWithEtags() throws Exception {
        SavedPrompt request = new SavedPrompt("name", "edited");
        when(service.update(request, "\"v1\""))
                .thenReturn(new SavedPromptDetails("name", "edited", "\"v2\""));
        when(service.rename(new SavedPromptRenameRequest("name", "New  name"), "\"v2\""))
                .thenReturn(new SavedPromptDetails("New  name", "edited", "\"v3\""));
        mvc.perform(put("/api/saved-prompts").header("If-Match", "\"v1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"name\",\"prompt\":\"edited\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.etag").value("\"v2\""))
                .andExpect(header().string("ETag", "\"v2\""));
        mvc.perform(post("/api/saved-prompts/rename").header("If-Match", "\"v2\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"name\",\"newName\":\"New  name\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("New  name"))
                .andExpect(jsonPath("$.etag").value("\"v3\"")).andExpect(header().string("ETag", "\"v3\""));
    }

    @Test
    void mutationEndpointsRequireIfMatchHeader() throws Exception {
        mvc.perform(put("/api/saved-prompts").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"name\",\"prompt\":\"text\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/saved-prompts/rename").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"name\",\"newName\":\"new\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(delete("/api/saved-prompts").param("name", "name")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void mutationEndpointsReportStaleEtagsAndMissingPrompts() throws Exception {
        BlobStorageException stale = mock(BlobStorageException.class);
        when(stale.getErrorCode()).thenReturn(BlobErrorCode.CONDITION_NOT_MET);
        when(stale.getStatusCode()).thenReturn(412);
        when(service.update(any(), anyString())).thenThrow(stale);
        when(service.rename(any(), anyString())).thenThrow(stale);
        when(service.delete(anyString(), anyString())).thenThrow(stale);
        mvc.perform(put("/api/saved-prompts").header("If-Match", "\"old\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"name\",\"prompt\":\"text\"}"))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.error").exists());
        mvc.perform(post("/api/saved-prompts/rename").header("If-Match", "\"old\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"name\",\"newName\":\"new\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(delete("/api/saved-prompts").param("name", "name").header("If-Match", "\"old\""))
                .andExpect(status().isPreconditionFailed());
        BlobStorageException missing = mock(BlobStorageException.class);
        when(missing.getStatusCode()).thenReturn(404);
        doThrow(missing).when(service).update(any(), anyString());
        mvc.perform(put("/api/saved-prompts").header("If-Match", "\"old\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"name\",\"prompt\":\"text\"}")).andExpect(status().isNotFound());
    }

    @Test
    void reportsRenameConflictAndIncompleteRename() throws Exception {
        doThrow(new SavedPromptNameConflictException(new IllegalStateException("name conflict")))
                .when(service).rename(any(), anyString());
        mvc.perform(post("/api/saved-prompts/rename").header("If-Match", "\"v1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"name\",\"newName\":\"taken\"}"))
                .andExpect(status().isConflict());
        doThrow(new SavedPromptRenameException(new IllegalStateException("timeout")))
                .when(service).rename(any(), anyString());
        mvc.perform(post("/api/saved-prompts/rename").header("If-Match", "\"v1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"name\",\"newName\":\"new\"}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value(
                        "Rename could not finish reliably. Both names may be present; refresh before retrying."));
    }
}
