package io.weidongxu.webapp.imagecreator;

import java.util.List;

public record PagedImageResponse(List<ImageInfo> images, int page, int totalPages, long totalImages) {
}
