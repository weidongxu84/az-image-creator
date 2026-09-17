package io.weidongxu.webapp.imagecreator;

import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageGenerateParams;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void routesFlareToAlternateDeployment() {
        AppConfig config = mock(AppConfig.class);
        when(config.isUseAlternateImageEndpoint()).thenReturn(true);
        when(config.getOpenAIEndpoint()).thenReturn("https://primary.openai.azure.com");
        when(config.getAlternateImageEndpoint()).thenReturn("https://secondary.openai.azure.com");
        when(config.getAlternateImageApiKey()).thenReturn("test-key");
        when(config.getAlternateImageDeployment()).thenReturn("gpt-image-2-secondary");
        when(config.getAlternateImageFlareDeployment()).thenReturn("gpt-image-2.5-flare-secondary");

        OpenAIService service = new OpenAIService(config, mock(ChatResponseMapper.class));

        assertThat(service.getImageDeployment("gpt-image-2"))
                .isEqualTo("gpt-image-2-secondary");
        assertThat(service.getImageDeployment("gpt-image-2.5-flare"))
                .isEqualTo("gpt-image-2.5-flare-secondary");
    }
}
