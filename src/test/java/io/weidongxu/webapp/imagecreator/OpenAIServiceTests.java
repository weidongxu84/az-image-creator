package io.weidongxu.webapp.imagecreator;

import com.openai.client.OpenAIClient;
import com.openai.core.ClientOptions;
import com.openai.models.images.ImageEditParams;
import com.openai.models.images.ImageGenerateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenAIServiceTests {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void increasesRetriesAndTimeoutOnlyForImageClients(boolean alternateEndpoint) {
        AppConfig config = mock(AppConfig.class);
        when(config.isUseAlternateImageEndpoint()).thenReturn(alternateEndpoint);
        when(config.getOpenAIEndpoint()).thenReturn("https://primary.openai.azure.com");
        when(config.getOpenAIApiKey()).thenReturn("test-primary-key");
        when(config.getAlternateImageEndpoint()).thenReturn("https://secondary.openai.azure.com");
        when(config.getAlternateImageApiKey()).thenReturn("test-secondary-key");

        OpenAIService service = new OpenAIService(config, mock(ChatResponseMapper.class));
        List<OpenAIClient> clients = new ArrayList<>();
        try {
            for (String field : List.of("imageClient", "imageFallbackClient",
                    "managedIdentityChatClient", "apiKeyFallbackChatClient")) {
                OpenAIClient client = (OpenAIClient) ReflectionTestUtils.getField(service, field);
                if (alternateEndpoint && "imageFallbackClient".equals(field)) {
                    assertThat(client).isNull();
                    continue;
                }
                assertThat(client).isNotNull();
                clients.add(client);
                ClientOptions options = (ClientOptions) ReflectionTestUtils.getField(client, "clientOptions");
                assertThat(options.maxRetries()).as(field)
                        .isEqualTo(field.startsWith("image") ? 5 : 2);
                assertThat(options.timeout().request()).as(field)
                        .isEqualTo(Duration.ofMinutes(field.startsWith("image") ? 20 : 10));
            }
        } finally {
            clients.forEach(OpenAIClient::close);
        }
    }

    @Test
    void usesModelSpecificImageQuality() {
        assertThat(OpenAIService.generateQuality("gpt-image-2.5-flare"))
                .isEqualTo(ImageGenerateParams.Quality.MAX);
        assertThat(OpenAIService.editQuality("gpt-image-2.5-flare"))
                .isEqualTo(ImageEditParams.Quality.MAX);
        assertThat(OpenAIService.generateQuality("gpt-image-2.5-sunburst"))
                .isEqualTo(ImageGenerateParams.Quality.XHIGH);
        assertThat(OpenAIService.editQuality("gpt-image-2.5-sunburst"))
                .isEqualTo(ImageEditParams.Quality.XHIGH);
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
        when(config.getAlternateImageSunburstDeployment()).thenReturn("gpt-image-2.5-sunburst-secondary");

        OpenAIService service = new OpenAIService(config, mock(ChatResponseMapper.class));

        assertThat(service.getImageDeployment("gpt-image-2"))
                .isEqualTo("gpt-image-2-secondary");
        assertThat(service.getImageDeployment("gpt-image-2.5-flare"))
                .isEqualTo("gpt-image-2.5-flare-secondary");
        assertThat(service.getImageDeployment("gpt-image-2.5-sunburst"))
                .isEqualTo("gpt-image-2.5-sunburst-secondary");
    }
}
