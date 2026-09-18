package io.weidongxu.webapp.imagecreator;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageControllerTests {

    @Test
    void reportsActiveGenerationJobs() {
        JobStore jobs = new JobStore();
        jobs.createJob();
        String runningJob = jobs.createJob();
        jobs.setRunning(runningJob);
        String completedJob = jobs.createJob();
        jobs.setCompleted(completedJob, java.util.List.of("image.png"));

        ImageController controller = new ImageController();
        ReflectionTestUtils.setField(controller, "jobStore", jobs);

        ResponseEntity<Map<String, Long>> response = controller.getActiveJobCount();

        assertThat(response.getBody()).containsEntry("activeJobs", 2L);
    }

    @Test
    void combinesMonthAndPromptFiltersWhenListingImages() {
        StorageService storage = mock(StorageService.class);
        when(storage.listImages("2026/08/", "sunset")).thenReturn(List.of(
                new ImageInfo("2026/08/01/image.png", "2026-08-01T00:00:00Z", "A sunset")));
        ImageController controller = new ImageController();
        ReflectionTestUtils.setField(controller, "storageService", storage);

        ResponseEntity<PagedImageResponse> response =
                controller.listImages(0, 6, "2026/08/", "sunset");

        assertThat(response.getBody().totalImages()).isEqualTo(1);
        assertThat(response.getBody().images()).hasSize(1);
    }

    @Test
    void returnsOrientationValidationWithoutCreatingGenerationJob() {
        OpenAIService openAI = mock(OpenAIService.class);
        ImageGenerationService generation = mock(ImageGenerationService.class);
        ImageOrientationValidation validation = new ImageOrientationValidation();
        validation.intended_orientation = "portrait";
        validation.selected_orientation = "portrait";
        validation.matches = true;
        validation.confidence = "high";
        validation.reason = "The prompt explicitly requests a portrait.";
        when(openAI.validateImageOrientation("vertical portrait", "2448x3264"))
                .thenReturn(validation);

        ImageController controller = new ImageController();
        ReflectionTestUtils.setField(controller, "openAIService", openAI);
        ReflectionTestUtils.setField(controller, "imageGenerationService", generation);

        ResponseEntity<?> response =
                controller.validateOrientation("vertical portrait", "2448x3264");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isSameAs(validation);
        verifyNoInteractions(generation);
    }

    @Test
    void rejectsOrientationMismatchBeforeCreatingGenerationJob() throws Exception {
        OpenAIService openAI = mock(OpenAIService.class);
        ImageGenerationService generation = mock(ImageGenerationService.class);
        ImageRequestValidation validation = new ImageRequestValidation();
        validation.intended_orientation = "portrait";
        validation.selected_orientation = "landscape";
        validation.orientation_matches = false;
        validation.orientation_confidence = "high";
        validation.orientation_reason = "The prompt explicitly requests a vertical portrait.";
        validation.input_image_intent = "generation";
        validation.minimum_input_images = 0;
        validation.provided_input_images = 0;
        validation.input_images_match = true;
        validation.input_image_confidence = "high";
        validation.input_image_reason = "No input images are required.";
        when(openAI.validateImageRequest("vertical portrait", "3264x2448", 0))
                .thenReturn(validation);

        ImageController controller = new ImageController();
        ReflectionTestUtils.setField(controller, "openAIService", openAI);
        ReflectionTestUtils.setField(controller, "imageGenerationService", generation);
        ReflectionTestUtils.setField(controller, "jobStore", new JobStore());

        ResponseEntity<?> response = controller.generate(
                "vertical portrait", "gpt-image-2", "3264x2448",
                "png", 1, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) response.getBody()).get("validation"))
                .isInstanceOf(ImageOrientationValidation.class);
        verifyNoInteractions(generation);
    }

    @Test
    void rejectsInputImageShortageAfterOrientationValidation() throws Exception {
        OpenAIService openAI = mock(OpenAIService.class);
        ImageGenerationService generation = mock(ImageGenerationService.class);
        ImageRequestValidation validation = new ImageRequestValidation();
        validation.intended_orientation = "unspecified";
        validation.selected_orientation = "portrait";
        validation.orientation_matches = true;
        validation.orientation_confidence = "high";
        validation.orientation_reason = "No orientation was specified.";
        validation.input_image_intent = "multi_image_edit";
        validation.minimum_input_images = 2;
        validation.provided_input_images = 1;
        validation.input_images_match = false;
        validation.input_image_confidence = "high";
        validation.input_image_reason = "The prompt requires two source images.";
        when(openAI.validateImageRequest("Two-Subject Image Edit", "2448x3264", 1))
                .thenReturn(validation);

        ImageController controller = new ImageController();
        ReflectionTestUtils.setField(controller, "openAIService", openAI);
        ReflectionTestUtils.setField(controller, "imageGenerationService", generation);
        ReflectionTestUtils.setField(controller, "jobStore", new JobStore());
        MockMultipartFile image =
                new MockMultipartFile("images", "subject.png", "image/png", new byte[] { 1 });

        ResponseEntity<?> response = controller.generate(
                "Two-Subject Image Edit", "gpt-image-2", "2448x3264",
                "png", 1, List.of(image), null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) response.getBody()).get("inputImageValidation"))
                .isInstanceOf(InputImageValidation.class);
        verify(openAI).validateImageRequest("Two-Subject Image Edit", "2448x3264", 1);
        verifyNoInteractions(generation);
    }
}
