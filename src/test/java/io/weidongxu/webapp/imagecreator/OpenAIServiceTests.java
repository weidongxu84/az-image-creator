package io.weidongxu.webapp.imagecreator;

import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageGenerateParams;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAIServiceTests {

    @Test
    void usesMaxQualityOnlyForFlare() {
        assertThat(OpenAIService.generateQuality("gpt-image-2.5-flare"))
                .isEqualTo(ImageGenerateParams.Quality.MAX);
        assertThat(OpenAIService.editQuality("gpt-image-2.5-flare"))
                .isEqualTo(ImageEditParams.Quality.MAX);
        assertThat(OpenAIService.generateQuality("gpt-image-2"))
                .isEqualTo(ImageGenerateParams.Quality.HIGH);
        assertThat(OpenAIService.editQuality("gpt-image-2"))
                .isEqualTo(ImageEditParams.Quality.HIGH);
    }
}
