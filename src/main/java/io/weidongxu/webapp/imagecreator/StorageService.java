package io.weidongxu.webapp.imagecreator;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.core.util.BinaryData;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class StorageService {

    private final BlobContainerClient containerClient;

    public StorageService(AppConfig config) {
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .endpoint("https://" + config.getStorageAccountName() + ".blob.core.windows.net")
                .credential(config.getCredential())
                .buildClient();
        this.containerClient = serviceClient.getBlobContainerClient(config.getStorageContainerName());
    }

    public String upload(byte[] imageData, String prompt, String outputFormat) {
        String ext = switch (outputFormat == null ? "png" : outputFormat.toLowerCase()) {
            case "jpeg" -> ".jpg";
            case "webp" -> ".webp";
            default -> ".png";
        };
        String contentType = switch (ext) {
            case ".jpg" -> "image/jpeg";
            case ".webp" -> "image/webp";
            default -> "image/png";
        };

        String blobName = "image_"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))
                + ext;

        Map<String, String> metadata = new HashMap<>();
        if (prompt != null && !prompt.isBlank()) {
            String truncated = prompt.length() > 4000 ? prompt.substring(0, 4000) : prompt;
            metadata.put("prompt", truncated.replace("\r", " ").replace("\n", " "));
        }

        BlobParallelUploadOptions options = new BlobParallelUploadOptions(BinaryData.fromBytes(imageData))
                .setHeaders(new BlobHttpHeaders().setContentType(contentType))
                .setMetadata(metadata);

        containerClient.getBlobClient(blobName).uploadWithResponse(options, null, null);
        return blobName;
    }

    public List<ImageInfo> listImages() {
        ListBlobsOptions options = new ListBlobsOptions()
                .setDetails(new BlobListDetails().setRetrieveMetadata(true));

        return containerClient.listBlobs(options, null).stream()
                .sorted(Comparator.comparing(
                        (BlobItem item) -> item.getProperties().getLastModified(),
                        Comparator.reverseOrder()))
                .map(item -> new ImageInfo(
                        item.getName(),
                        item.getProperties().getLastModified().toString(),
                        item.getMetadata() != null
                                ? item.getMetadata().getOrDefault("prompt", "")
                                : ""))
                .collect(Collectors.toList());
    }

    public byte[] download(String blobName) {
        return containerClient.getBlobClient(blobName).downloadContent().toBytes();
    }

    public void delete(String blobName) {
        containerClient.getBlobClient(blobName).delete();
    }
}
