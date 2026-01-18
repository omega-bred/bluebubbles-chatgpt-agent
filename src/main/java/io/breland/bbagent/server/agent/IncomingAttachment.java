package io.breland.bbagent.server.agent;

public record IncomingAttachment(
    String guid, String mimeType, String filename, String url, String dataUrl, String base64) {}
