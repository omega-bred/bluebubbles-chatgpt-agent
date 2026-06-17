package io.breland.bbagent.server.agent;

import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputFile;
import com.openai.models.responses.ResponseInputImage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public final class AgentAttachmentInputBuilder {
  private static final int MAX_IMAGE_ATTACHMENTS = 4;
  private static final int MAX_FILE_ATTACHMENTS = 4;

  private final BBHttpClientWrapper bbHttpClientWrapper;

  public AgentAttachmentInputBuilder(BBHttpClientWrapper bbHttpClientWrapper) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  ResolvedAttachments resolve(IncomingMessage message) {
    if (message == null || message.attachments() == null || message.attachments().isEmpty()) {
      return ResolvedAttachments.empty();
    }
    List<String> imageUrls =
        message.attachments().stream()
            .map(this::resolveAttachmentImageUrl)
            .flatMap(Optional::stream)
            .toList();
    List<ResponseInputFile> files =
        message.attachments().stream()
            .map(this::resolveAttachmentFile)
            .flatMap(Optional::stream)
            .toList();
    return new ResolvedAttachments(imageUrls, files);
  }

  List<String> resolveImageUrls(IncomingMessage message) {
    return resolve(message).imageUrls();
  }

  private Optional<String> resolveAttachmentImageUrl(IncomingAttachment attachment) {
    if (attachment == null || isKnownNonImage(attachment.mimeType())) {
      return Optional.empty();
    }
    if (StringUtils.isNotBlank(attachment.dataUrl())) {
      return attachment.dataUrl().startsWith("data:image/")
          ? Optional.of(attachment.dataUrl())
          : Optional.empty();
    }
    if (StringUtils.isNotBlank(attachment.base64()) && isImageMime(attachment.mimeType())) {
      return Optional.of(dataUrl(attachment.mimeType(), attachment.base64()));
    }
    if (StringUtils.isNotBlank(attachment.url())) {
      return Optional.of(attachment.url());
    }
    return loadAttachmentData(attachment)
        .map(data -> dataUrl(defaultMimeType(data.mimeType()), data.base64()));
  }

  private Optional<ResponseInputFile> resolveAttachmentFile(IncomingAttachment attachment) {
    if (attachment == null || isImageMime(attachment.mimeType())) {
      return Optional.empty();
    }
    return loadAttachmentData(attachment)
        .map(
            data ->
                ResponseInputFile.builder()
                    .fileData(data.base64())
                    .filename(defaultFilename(data.filename(), attachment.filename()))
                    .build());
  }

  private Optional<AttachmentData> loadAttachmentData(IncomingAttachment attachment) {
    if (attachment == null) {
      return Optional.empty();
    }
    Optional<AttachmentData> downloaded = downloadAttachmentData(attachment);
    if (downloaded.isPresent()) {
      return downloaded;
    }
    if (StringUtils.isNotBlank(attachment.base64())) {
      return Optional.of(new AttachmentData(attachment.base64(), attachment.mimeType(), null));
    }
    if (StringUtils.isNotBlank(attachment.dataUrl())) {
      String dataUrl = attachment.dataUrl();
      int comma = dataUrl.indexOf(',');
      if (comma > 0 && comma < dataUrl.length() - 1) {
        return Optional.of(
            new AttachmentData(dataUrl.substring(comma + 1), mimeType(dataUrl), null));
      }
    }
    return Optional.empty();
  }

  private Optional<AttachmentData> downloadAttachmentData(IncomingAttachment attachment) {
    if (StringUtils.isBlank(attachment.guid())) {
      return Optional.empty();
    }
    Path path = null;
    try {
      path = bbHttpClientWrapper.getAttachment(attachment.guid());
      if (path == null) {
        return Optional.empty();
      }
      String mimeType = StringUtils.defaultIfBlank(attachment.mimeType(), probeContentType(path));
      String filename = path.getFileName() == null ? null : path.getFileName().toString();
      return Optional.of(
          new AttachmentData(
              Base64.getEncoder().encodeToString(Files.readAllBytes(path)), mimeType, filename));
    } catch (Exception e) {
      log.warn("Failed to download attachment {}", attachment.guid(), e);
      return Optional.empty();
    } finally {
      deleteIfExists(path);
    }
  }

  private static boolean isImageMime(@Nullable String mimeType) {
    return mimeType != null && mimeType.startsWith("image/");
  }

  private static boolean isKnownNonImage(@Nullable String mimeType) {
    return mimeType != null && !mimeType.startsWith("image/");
  }

  private static String dataUrl(String mimeType, String base64) {
    return "data:" + defaultMimeType(mimeType) + ";base64," + base64.trim();
  }

  private static String defaultMimeType(@Nullable String mimeType) {
    return StringUtils.defaultIfBlank(mimeType, "image/png");
  }

  private static String defaultFilename(
      @Nullable String resolvedFilename, @Nullable String fallback) {
    return StringUtils.defaultIfBlank(
        StringUtils.defaultIfBlank(resolvedFilename, fallback), "attachment");
  }

  private static @Nullable String mimeType(String dataUrl) {
    if (dataUrl == null || !dataUrl.startsWith("data:")) {
      return null;
    }
    int semicolon = dataUrl.indexOf(';');
    if (semicolon <= "data:".length()) {
      return null;
    }
    return dataUrl.substring("data:".length(), semicolon);
  }

  private static @Nullable String probeContentType(Path path) {
    try {
      return Files.probeContentType(path);
    } catch (Exception ignored) {
      return null;
    }
  }

  private static void deleteIfExists(@Nullable Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (Exception ignored) {
      // best effort cleanup
    }
  }

  record ResolvedAttachments(List<String> imageUrls, List<ResponseInputFile> files) {
    static ResolvedAttachments empty() {
      return new ResolvedAttachments(List.of(), List.of());
    }

    int imageCount() {
      return imageUrls.size();
    }

    int fileCount() {
      return files.size();
    }

    List<ResponseInputContent> inputContent() {
      List<ResponseInputContent> content = new ArrayList<>();
      imageUrls.stream()
          .limit(MAX_IMAGE_ATTACHMENTS)
          .map(
              url ->
                  ResponseInputContent.ofInputImage(
                      ResponseInputImage.builder()
                          .detail(ResponseInputImage.Detail.AUTO)
                          .imageUrl(url)
                          .build()))
          .forEach(content::add);
      files.stream()
          .limit(MAX_FILE_ATTACHMENTS)
          .map(ResponseInputContent::ofInputFile)
          .forEach(content::add);
      return content;
    }
  }

  private record AttachmentData(
      String base64, @Nullable String mimeType, @Nullable String filename) {}
}
