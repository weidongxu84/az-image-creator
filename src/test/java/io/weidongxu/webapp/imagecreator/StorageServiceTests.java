package io.weidongxu.webapp.imagecreator;

import com.azure.core.credential.TokenCredential;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.UserDelegationKey;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageServiceTests {

    private static final String ACCOUNT_KEY =
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

    @Test
    void buildsBlobClientFromAccountKeyWhenConfigured() {
        AppConfig config = mock(AppConfig.class);
        when(config.hasStorageAccountKey()).thenReturn(true);
        when(config.getStorageAccountName()).thenReturn("testaccount");
        when(config.getStorageAccountKey()).thenReturn(ACCOUNT_KEY);

        BlobServiceClient client = StorageService.buildBlobServiceClient(config);

        assertThat(client.getAccountUrl()).isEqualTo("https://testaccount.blob.core.windows.net");
    }

    @Test
    void buildsBlobClientFromManagedIdentityWhenAccountKeyIsAbsent() {
        AppConfig config = mock(AppConfig.class);
        when(config.hasStorageAccountKey()).thenReturn(false);
        when(config.getStorageAccountName()).thenReturn("managedaccount");
        when(config.getCredential()).thenReturn(mock(TokenCredential.class));

        BlobServiceClient client = StorageService.buildBlobServiceClient(config);

        assertThat(client.getAccountUrl()).isEqualTo("https://managedaccount.blob.core.windows.net");
    }

    @Test
    void usesSharedKeyForSasWhenConfiguredWithConnectionString() {
        BlobServiceClient serviceClient = mock(BlobServiceClient.class);
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        when(serviceClient.getBlobContainerClient("images")).thenReturn(containerClient);
        when(containerClient.getBlobClient("2026/09/18/image.png")).thenReturn(blobClient);
        when(blobClient.generateSas(any(BlobServiceSasSignatureValues.class))).thenReturn("shared-key-sas");
        when(blobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/images/image.png");

        StorageService service = new StorageService(
                serviceClient, "images", mock(PromptStorageService.class), true);

        assertThat(service.generateSasUrl("2026/09/18/image.png"))
                .endsWith("?shared-key-sas");
        verify(serviceClient, never()).getUserDelegationKey(any(), any());
    }

    @Test
    void usesUserDelegationForSasWithManagedIdentity() {
        BlobServiceClient serviceClient = mock(BlobServiceClient.class);
        BlobContainerClient containerClient = mock(BlobContainerClient.class);
        BlobClient blobClient = mock(BlobClient.class);
        UserDelegationKey delegationKey = mock(UserDelegationKey.class);
        when(serviceClient.getBlobContainerClient("images")).thenReturn(containerClient);
        when(containerClient.getBlobClient("2026/09/18/image.png")).thenReturn(blobClient);
        when(serviceClient.getUserDelegationKey(any(), any())).thenReturn(delegationKey);
        when(blobClient.generateUserDelegationSas(
                any(BlobServiceSasSignatureValues.class), any(UserDelegationKey.class)))
                .thenReturn("delegation-sas");
        when(blobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/images/image.png");

        StorageService service = new StorageService(
                serviceClient, "images", mock(PromptStorageService.class), false);

        assertThat(service.generateSasUrl("2026/09/18/image.png"))
                .endsWith("?delegation-sas");
        verify(blobClient, never()).generateSas(any(BlobServiceSasSignatureValues.class));
    }
}
