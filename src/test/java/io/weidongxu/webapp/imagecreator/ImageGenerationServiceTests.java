package io.weidongxu.webapp.imagecreator;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageGenerationServiceTests {

    @Test
    void completesOnlyAfterSavingPrompt() {
        OpenAIService openAI = mock(OpenAIService.class);
        FluxService flux = mock(FluxService.class);
        StorageService storage = mock(StorageService.class);
        PromptStorageService prompts = mock(PromptStorageService.class);
        JobStore jobs = new JobStore();
        AppConfig config = mock(AppConfig.class);
        when(openAI.getImageDeployment("gpt-image-2")).thenReturn("gpt-image-2");
        when(openAI.generateImage("gpt-image-2", "prompt", "1024x1024", "png", 1))
                .thenReturn(List.of(new byte[]{1}));
        when(storage.upload(any(), eq("png"))).thenReturn("2026/07/31/image.png");

        String jobId = jobs.createJob();
        service(openAI, flux, storage, prompts, jobs, config)
                .generateImage(jobId, "gpt-image-2", "prompt", "1024x1024",
                        null, null, null, "png", 1);

        assertThat(jobs.getJob(jobId).status()).isEqualTo("completed");
        ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
        verify(prompts).save(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("gpt-image-2");
        assertThat(captor.getValue().provider()).isEqualTo("azure-openai");
        assertThat(captor.getValue().operation()).isEqualTo("generate");
        assertThat(captor.getValue().referenceImageCount()).isZero();
    }

    @Test
    void keepsImageWhenPromptSaveFails() {
        OpenAIService openAI = mock(OpenAIService.class);
        FluxService flux = mock(FluxService.class);
        StorageService storage = mock(StorageService.class);
        PromptStorageService prompts = mock(PromptStorageService.class);
        JobStore jobs = new JobStore();
        AppConfig config = mock(AppConfig.class);
        when(openAI.getImageDeployment("gpt-image-2")).thenReturn("gpt-image-2");
        when(openAI.generateImage(any(), any(), any(), any(), eq(1))).thenReturn(List.of(new byte[]{1}));
        when(storage.upload(any(), eq("png"))).thenReturn("2026/07/31/image.png");
        doThrow(new RuntimeException("table unavailable")).when(prompts).save(any());

        String jobId = jobs.createJob();
        service(openAI, flux, storage, prompts, jobs, config)
                .generateImage(jobId, "gpt-image-2", "prompt", "1024x1024",
                        null, null, null, "png", 1);

        assertThat(jobs.getJob(jobId).status()).isEqualTo("completed");
        assertThat(jobs.getJob(jobId).imageNames()).containsExactly("2026/07/31/image.png");
    }

    @Test
    void recordsAlternateImageEndpointProvider() {
        OpenAIService openAI = mock(OpenAIService.class);
        FluxService flux = mock(FluxService.class);
        StorageService storage = mock(StorageService.class);
        PromptStorageService prompts = mock(PromptStorageService.class);
        JobStore jobs = new JobStore();
        AppConfig config = mock(AppConfig.class);
        when(config.isUseAlternateImageEndpoint()).thenReturn(true);
        when(openAI.getImageDeployment("gpt-image-2")).thenReturn("gpt-image-2");
        when(openAI.generateImage("gpt-image-2", "prompt", "1024x1024", "png", 1))
                .thenReturn(List.of(new byte[]{1}));
        when(storage.upload(any(), eq("png"))).thenReturn("2026/07/31/image.png");

        String jobId = jobs.createJob();
        service(openAI, flux, storage, prompts, jobs, config)
                .generateImage(jobId, "gpt-image-2", "prompt", "1024x1024",
                        null, null, null, "png", 1);

        ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
        verify(prompts).save(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("gpt-image-2");
        assertThat(captor.getValue().provider()).isEqualTo("azure-openai-alternate");
    }

    @Test
    void recordsSelectedFlareDeployment() {
        OpenAIService openAI = mock(OpenAIService.class);
        FluxService flux = mock(FluxService.class);
        StorageService storage = mock(StorageService.class);
        PromptStorageService prompts = mock(PromptStorageService.class);
        JobStore jobs = new JobStore();
        AppConfig config = mock(AppConfig.class);
        when(openAI.getImageDeployment("gpt-image-2.5-flare")).thenReturn("gpt-image-2.5-flare");
        when(openAI.generateImage("gpt-image-2.5-flare", "prompt", "1024x1024", "png", 1))
                .thenReturn(List.of(new byte[]{1}));
        when(storage.upload(any(), eq("png"))).thenReturn("2026/09/16/image.png");

        String jobId = jobs.createJob();
        service(openAI, flux, storage, prompts, jobs, config)
                .generateImage(jobId, "gpt-image-2.5-flare", "prompt", "1024x1024",
                        null, null, null, "png", 1);

        ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
        verify(prompts).save(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("gpt-image-2.5-flare");
        assertThat(captor.getValue().provider()).isEqualTo("azure-openai");
    }

    @Test
    void recordsSelectedSunburstDeployment() {
        OpenAIService openAI = mock(OpenAIService.class);
        FluxService flux = mock(FluxService.class);
        StorageService storage = mock(StorageService.class);
        PromptStorageService prompts = mock(PromptStorageService.class);
        JobStore jobs = new JobStore();
        AppConfig config = mock(AppConfig.class);
        when(openAI.getImageDeployment("gpt-image-2.5-sunburst")).thenReturn("gpt-image-2.5-sunburst");
        when(openAI.generateImage("gpt-image-2.5-sunburst", "prompt", "1024x1024", "png", 1))
                .thenReturn(List.of(new byte[]{1}));
        when(storage.upload(any(), eq("png"))).thenReturn("2026/09/17/image.png");

        String jobId = jobs.createJob();
        service(openAI, flux, storage, prompts, jobs, config)
                .generateImage(jobId, "gpt-image-2.5-sunburst", "prompt", "1024x1024",
                        null, null, null, "png", 1);

        ArgumentCaptor<ImagePrompt> captor = ArgumentCaptor.forClass(ImagePrompt.class);
        verify(prompts).save(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("gpt-image-2.5-sunburst");
        assertThat(captor.getValue().provider()).isEqualTo("azure-openai");
    }

    private ImageGenerationService service(OpenAIService openAI, FluxService flux, StorageService storage,
                                           PromptStorageService prompts, JobStore jobs, AppConfig config) {
        return new ImageGenerationService(openAI, flux, storage, prompts, jobs, config);
    }
}
