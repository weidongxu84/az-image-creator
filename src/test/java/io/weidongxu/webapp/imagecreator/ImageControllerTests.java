package io.weidongxu.webapp.imagecreator;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ImageControllerTests {

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
        ImageOrientationValidation validation = new ImageOrientationValidation();
        validation.intended_orientation = "portrait";
        validation.selected_orientation = "landscape";
        validation.matches = false;
        validation.confidence = "high";
        validation.reason = "The prompt explicitly requests a vertical portrait.";
        when(openAI.validateImageOrientation("vertical portrait", "3264x2448"))
                .thenReturn(validation);

        ImageController controller = new ImageController();
        ReflectionTestUtils.setField(controller, "openAIService", openAI);
        ReflectionTestUtils.setField(controller, "imageGenerationService", generation);
        ReflectionTestUtils.setField(controller, "jobStore", new JobStore());

        ResponseEntity<?> response = controller.generate(
                "vertical portrait", "gpt-image-2", "3264x2448",
                "png", 1, "low", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) response.getBody()).get("validation")).isSameAs(validation);
        verifyNoInteractions(generation);
    }
}
