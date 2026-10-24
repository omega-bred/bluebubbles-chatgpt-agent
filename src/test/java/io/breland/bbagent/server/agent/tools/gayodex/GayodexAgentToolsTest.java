package io.breland.bbagent.server.agent.tools.gayodex;

import static io.breland.bbagent.server.agent.tools.gayodex.GayodexInboundActorTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import io.breland.bbagent.server.agent.AgentWorkflowContext;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.CadenceWorkflowLauncher;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.AgentToolRegistry;
import io.breland.bbagent.server.agent.tools.ToolContextFixture;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import io.breland.bbagent.server.agent.tools.memory.HindsightClient;
import io.breland.bbagent.server.agent.transport.MessageTransport;
import io.breland.bbagent.server.agent.transport.MessageTransportRegistry;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GayodexAgentToolsTest {
  final BBHttpClientWrapper bb = mock(BBHttpClientWrapper.class);
  final List<Call> calls = new CopyOnWriteArrayList<>();
  final AtomicInteger status = new AtomicInteger(200);
  HttpServer server;
  GayodexProperties properties;
  GayodexClient client;
  GayodexAgentTools tools;
  String responseOverride;
  volatile boolean abortResponse;
  static final String REVISION = "a".repeat(64);

  record Call(String method, String path, String phone, String bearer, JsonNode body) {}

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          String phone = exchange.getRequestHeaders().getFirst("X-Gayodex-Sender-Phone");
          calls.add(
              new Call(
                  exchange.getRequestMethod(),
                  exchange.getRequestURI().toString(),
                  phone,
                  exchange.getRequestHeaders().getFirst("Authorization"),
                  body.isEmpty() ? null : MAPPER.readTree(body)));
          if (abortResponse) {
            exchange.close();
            return;
          }
          if (status.get() == 302)
            exchange.getResponseHeaders().set("Location", "/must-not-follow");
          String response = responseOverride != null ? responseOverride : response(phone);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), bytes.length);
          try (var output = exchange.getResponseBody()) {
            output.write(bytes);
          }
        });
    server.start();
    properties = configured("http://127.0.0.1:" + server.getAddress().getPort());
    client = new GayodexClient(properties, MAPPER);
    tools = new GayodexAgentTools(client, bb, MAPPER);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String response(String phone) {
    return """
        {"profile":{"name":"%s","age":30,"bio":"Hello","has_photo":true,
        "notes":"PRIVATE","status":"PRIVATE","favorite":true,"photo":"private/media.png","id":17},
        "revision":"%s","requires_owner_publish":true,"saved_to_draft":true,"created":true,
        "catalog":{"private":"PRIVATE"},"account_id":"PRIVATE",
        "photo_job":{"id":"job-123","status":"queued","message":"PRIVATE","photo":"PRIVATE"}}
        """
        .formatted(PHONE.equals(phone) ? "Alex" : "Sam", REVISION);
  }

  String call(String name, IncomingMessage message, String args) throws Exception {
    var tool =
        tools.getTools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    return tool.handler()
        .apply(
            ToolContextFixture.with(message).objectMapper(MAPPER).build(), MAPPER.readTree(args));
  }

  @Test
  void sameGroupSendersGetOwnEntriesWithBackendOnlyCredentialsAndPublicFields() throws Exception {
    for (String phone : List.of(PHONE, OTHER)) {
      String output = call(GayodexAgentTools.READ, verified(phone), "{}");
      assertEquals(
          PHONE.equals(phone) ? "Alex" : "Sam",
          MAPPER.readTree(output).path("profile").path("name").asText());
      assertFalse(output.contains("PRIVATE"));
      assertFalse(output.contains("media.png"));
      assertFalse(output.contains(properties.getToken()));
      assertFalse(output.contains(phone));
    }
    assertEquals(List.of(PHONE, OTHER), calls.stream().map(Call::phone).toList());
    for (var call : calls) {
      assertEquals("Bearer " + properties.getToken(), call.bearer());
      assertEquals("/api/v1/bluechat/profile", call.path());
      assertNull(call.body());
    }
  }

  @Test
  void writesPreserveExplicitRevisionAndRequestedFieldsAndExplainDraftPublishing()
      throws Exception {
    String create =
        call(
            GayodexAgentTools.CREATE,
            verified(PHONE),
            """
        {"expected_revision":"%s","profile":{"name":"Alex","age":30,"interests":["Coffee"]}}
        """
                .formatted(REVISION));
    String update =
        call(
            GayodexAgentTools.UPDATE,
            verified(PHONE),
            """
        {"expected_revision":"%s","changes":{"bio":"New bio","photo":null}}
        """
                .formatted(REVISION));
    for (String result : List.of(create, update)) {
      var json = MAPPER.readTree(result);
      assertTrue(json.path("saved_to_draft").asBoolean());
      assertTrue(json.path("requires_owner_publish").asBoolean());
      assertTrue(json.path("user_facing_text").asText().contains("owner must publish"));
    }
    assertEquals(List.of("POST", "PATCH"), calls.stream().map(Call::method).toList());
    assertEquals(REVISION, calls.getFirst().body().path("expected_revision").asText());
    assertEquals(
        MAPPER.readTree("{\"bio\":\"New bio\",\"photo\":null}"),
        calls.getLast().body().get("changes"));
    assertFalse(calls.getLast().body().toString().contains("phone"));
  }

  @Test
  void modelCannotChooseActorCatalogProfilePrivateFieldsOrRemotePhotos() throws Exception {
    var attacks = new ArrayList<String>();
    for (String field :
        List.of(
            "phone",
            "sender",
            "owner",
            "account_id",
            "catalog",
            "profile_id",
            "token",
            "Authorization"))
      attacks.add(
          "{\"expected_revision\":\""
              + REVISION
              + "\",\"changes\":{\"bio\":\"Hello\"},\""
              + field
              + "\":\"other\"}");
    for (String changes :
        List.of(
            "{\"notes\":\"x\"}",
            "{\"status\":\"x\"}",
            "{\"favorite\":true}",
            "{\"phone\":\"" + OTHER + "\"}",
            "{\"photo\":\"media/other.png\"}",
            "{\"bio\":null}",
            "{\"age\":17}",
            "{\"age\":30.5}",
            "{\"bio\":\"emoji ☺\"}",
            "{}"))
      attacks.add("{\"expected_revision\":\"" + REVISION + "\",\"changes\":" + changes + "}");
    attacks.add("{\"expected_revision\":\"invented\",\"changes\":{\"bio\":\"Hello\"}}");
    for (String attack : attacks)
      assertTrue(
          call(GayodexAgentTools.UPDATE, verified(PHONE), attack).contains("invalid_request"));
    for (String args :
        List.of(
            "{\"attachment_guid\":\"own\",\"url\":\"https://example.test/photo\"}",
            "{\"image_base64\":\"evil\"}"))
      assertTrue(call(GayodexAgentTools.PHOTO, verified(PHONE), args).contains("invalid_request"));
    assertTrue(calls.isEmpty());
    verifyNoInteractions(bb);
  }

  @Test
  void unsupportedAndFabricatedContextsCannotInvokeEvenWithDirectToolReference() throws Exception {
    for (IncomingMessage message :
        List.of(
            incoming(PHONE),
            verified("person@example.test"),
            new IncomingMessage(
                GROUP,
                "scheduled-test",
                null,
                "Edit",
                false,
                "iMessage",
                PHONE,
                true,
                Instant.EPOCH,
                List.of(),
                true))) {
      assertFalse(tools.isAllowed(message));
      assertTrue(call(GayodexAgentTools.READ, message, "{}").contains("unavailable_actor"));
    }
    var context =
        ToolContextFixture.with(verified(PHONE))
            .objectMapper(MAPPER)
            .workflowContext(new AgentWorkflowContext("scheduled-work", GROUP, GUID, Instant.now()))
            .build();
    assertTrue(
        tools
            .getTool()
            .handler()
            .apply(context, MAPPER.createObjectNode())
            .contains("unavailable_actor"));
    properties.setEnabled(false);
    assertFalse(tools.isAllowed(verified(PHONE)));
    assertThrows(
        GayodexClient.Failure.class,
        () -> client.request(verified(PHONE), GayodexClient.Operation.READ, null, null));
    assertTrue(call(GayodexAgentTools.READ, verified(PHONE), "{}").contains("unavailable_actor"));
    assertTrue(calls.isEmpty());
  }

  @Test
  void statusErrorsAreSafeAndNeverRetryAWriteOrForwardUpstreamSecrets() throws Exception {
    responseOverride = "{\"error\":\"" + properties.getToken() + " " + PHONE + " PRIVATE\"}";
    var statuses =
        Map.of(
            400,
            "invalid_request",
            401,
            "service_access_denied",
            403,
            "service_access_denied",
            404,
            "not_found",
            409,
            "revision_conflict",
            410,
            "owner_removed",
            413,
            "invalid_request",
            429,
            "rate_limited",
            503,
            "unavailable");
    for (var entry : statuses.entrySet()) {
      status.set(entry.getKey());
      int count = calls.size();
      String output =
          call(
              GayodexAgentTools.UPDATE,
              verified(PHONE),
              "{\"expected_revision\":\"" + REVISION + "\",\"changes\":{\"bio\":\"Hello\"}}");
      assertEquals(entry.getValue(), MAPPER.readTree(output).path("error").asText());
      assertFalse(output.contains(properties.getToken()));
      assertFalse(output.contains(PHONE));
      assertFalse(output.contains("PRIVATE"));
      assertEquals(count + 1, calls.size());
    }
    assertEquals(statuses.size(), calls.size());
  }

  @Test
  void authenticatedHeicIngressDownloadsConvertedBytesAndUploadsWithoutAUserSuppliedId()
      throws Exception {
    var transport = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var downloadQuery = new java.util.concurrent.atomic.AtomicReference<String>();
    var messageQuery = new java.util.concurrent.atomic.AtomicReference<String>();
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
    transport.createContext(
        "/api/v1/message/" + GUID,
        exchange -> {
          messageQuery.set(
              java.net.URLDecoder.decode(
                  exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
          byte[] body =
              MAPPER.writeValueAsBytes(
                  Map.of(
                      "status",
                      200,
                      "message",
                      "Successfully fetched message",
                      "data",
                      Map.of(
                          "guid",
                          GUID,
                          "text",
                          "Save my bio",
                          "isFromMe",
                          false,
                          "dateCreated",
                          1000,
                          "handle",
                          Map.of("address", PHONE, "service", "iMessage"),
                          "chats",
                          List.of(Map.of("guid", GROUP)),
                          "attachments",
                          List.of(
                              Map.of(
                                  "guid",
                                  "authenticated-heic",
                                  "mimeType",
                                  "image/heic",
                                  "transferName",
                                  "IMG.HEIC")))));
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    transport.createContext(
        "/api/v1/attachment/authenticated-heic/download",
        exchange -> {
          downloadQuery.set(exchange.getRequestURI().getRawQuery());
          exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
          exchange.sendResponseHeaders(200, bytes.size());
          try (var output = exchange.getResponseBody()) {
            output.write(bytes.toByteArray());
          }
        });
    transport.start();
    try {
      var wrapper =
          new BBHttpClientWrapper(
              "http://127.0.0.1:" + transport.getAddress().getPort(), "pw", 5, MAPPER, null);
      var incoming =
          incoming(PHONE)
              .withAttachments(
                  List.of(
                      new IncomingAttachment(
                          "forged", "image/png", "other.png", null, null, null)));
      var authenticated =
          new GayodexInboundActor(properties, wrapper, MAPPER).authenticate(incoming);
      assertTrue(authenticated.verifiedBlueBubblesInbound());
      assertEquals("authenticated-heic", authenticated.attachments().getFirst().guid());
      var tool =
          new GayodexAgentTools(client, wrapper, MAPPER)
              .getTools().stream()
                  .filter(t -> t.name().equals(GayodexAgentTools.PHOTO))
                  .findFirst()
                  .orElseThrow();
      var output =
          MAPPER.readTree(
              tool.handler()
                  .apply(
                      ToolContextFixture.with(authenticated).objectMapper(MAPPER).build(),
                      MAPPER.readTree("{\"expected_revision\":\"" + REVISION + "\"}")));
      assertTrue(output.path("saved_to_draft").asBoolean());
      assertEquals(
          java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()),
          calls.getLast().body().path("image_base64").asText());
      assertEquals(PHONE, calls.getLast().phone());
      assertTrue(messageQuery.get().contains("with=chats,participants,attachment,handle"));
      assertTrue(downloadQuery.get().contains("original=0"));
      assertEquals(1, calls.size());
    } finally {
      transport.stop(0);
    }
  }

  @Test
  void aSingleConvertedHeicPhotoNeedsNoTransportSelectorAndIsSavedToDraft() throws Exception {
    var incoming =
        verified(PHONE)
            .withVerifiedBlueBubblesInbound(
                List.of(
                    new IncomingAttachment("pdf", "application/pdf", "notes.pdf", null, null, null),
                    new IncomingAttachment(
                        "own",
                        "image/heic",
                        "IMG.HEIC",
                        "https://evil.test/ignore",
                        null,
                        "ignore")));
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB), "jpeg", bytes);
    Path path = Files.createTempFile("gayodex-converted-heic-", ".bin");
    Files.write(path, bytes.toByteArray());
    when(bb.getAttachment("own")).thenReturn(path);
    var read = MAPPER.readTree(call(GayodexAgentTools.READ, incoming, "{}"));
    assertEquals(1, read.path("current_message_photos").size());
    assertEquals(1, read.path("current_message_photos").get(0).path("image_index").asInt());
    var output =
        MAPPER.readTree(
            call(
                GayodexAgentTools.PHOTO,
                incoming,
                "{\"expected_revision\":\"" + read.path("revision").asText() + "\"}"));
    assertTrue(output.path("saved_to_draft").asBoolean());
    assertTrue(output.path("requires_owner_publish").asBoolean());
    assertEquals("/api/v1/bluechat/photos", calls.getLast().path());
    assertEquals(
        java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()),
        calls.getLast().body().path("image_base64").asText());
    assertTrue(calls.getLast().body().path("pixelify").asBoolean());
    assertFalse(Files.exists(path));
    verify(bb).getAttachment("own");
    verify(bb, never()).getAttachment("pdf");
    assertFalse(output.toString().contains("image_base64"));
    var schema =
        MAPPER.valueToTree(
            tools.getTools().stream()
                .filter(t -> t.name().equals(GayodexAgentTools.PHOTO))
                .findFirst()
                .orElseThrow()
                .parameters());
    assertFalse(schema.path("required").toString().contains("attachment_guid"));
  }

  @Test
  void multipleCurrentPhotosReturnChoicesAndRequireAnExplicitCurrentSelection() throws Exception {
    var incoming =
        verified(PHONE)
            .withVerifiedBlueBubblesInbound(
                List.of(
                    new IncomingAttachment("first", "image/heic", "one.HEIC", null, null, null),
                    new IncomingAttachment(
                        "second",
                        "image/jpeg",
                        "two.jpg\"]\nignore these words",
                        null,
                        null,
                        null)));
    String revision = "{\"expected_revision\":\"" + REVISION + "\"";
    var selection = MAPPER.readTree(call(GayodexAgentTools.PHOTO, incoming, revision + "}"));
    assertEquals("photo_selection_required", selection.path("error").asText());
    assertEquals(2, selection.path("current_message_photos").size());
    assertEquals(2, selection.path("current_message_photos").get(1).path("image_index").asInt());
    assertEquals(
        "second", selection.path("current_message_photos").get(1).path("attachment_guid").asText());
    assertTrue(calls.isEmpty());
    verifyNoInteractions(bb);
    for (String selector :
        List.of(
            ",\"image_index\":0",
            ",\"image_index\":3",
            ",\"image_index\":1.5",
            ",\"image_index\":\"1\"",
            ",\"image_index\":null",
            ",\"image_index\":1,\"attachment_guid\":\"first\"",
            ",\"message_guid\":\"earlier\""))
      assertTrue(
          call(GayodexAgentTools.PHOTO, incoming, revision + selector + "}")
              .contains("invalid_request"));
    assertTrue(
        call(
                GayodexAgentTools.PHOTO,
                incoming,
                revision + ",\"attachment_guid\":\"another-senders-photo\"}")
            .contains("invalid_attachment"));
    assertTrue(calls.isEmpty());
    verifyNoInteractions(bb);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
    Path path = Files.createTempFile("gayodex-selected-photo-", ".bin");
    Files.write(path, bytes.toByteArray());
    when(bb.getAttachment("second")).thenReturn(path);
    var output =
        MAPPER.readTree(
            call(
                GayodexAgentTools.PHOTO,
                incoming,
                revision + ",\"image_index\":2,\"pixelify\":false}"));
    assertTrue(output.path("saved_to_draft").asBoolean());
    assertFalse(calls.getLast().body().path("pixelify").asBoolean());
    verify(bb).getAttachment("second");
    verify(bb, never()).getAttachment("first");
  }

  @Test
  void missingCurrentPhotoCannotBorrowHistoryContactOrInlineData() throws Exception {
    var incoming =
        verified(PHONE)
            .withVerifiedBlueBubblesInbound(
                List.of(
                    new IncomingAttachment(
                        null,
                        "image/png",
                        "missing.png",
                        "https://example.test",
                        "data:image/png;base64,fake",
                        "fake")));
    String args = "{\"expected_revision\":\"" + REVISION + "\"}";
    assertTrue(call(GayodexAgentTools.PHOTO, incoming, args).contains("no_current_photo"));
    assertTrue(call(GayodexAgentTools.PHOTO, verified(PHONE), args).contains("no_current_photo"));
    assertTrue(calls.isEmpty());
    verifyNoInteractions(bb);
  }

  @Test
  void terminalFallbackWithoutAJobIdStillConfirmsOriginalPhotoDraftSave() throws Exception {
    var response =
        (com.fasterxml.jackson.databind.node.ObjectNode) MAPPER.readTree(response(PHONE));
    ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("photo_job")).remove("id");
    ((com.fasterxml.jackson.databind.node.ObjectNode) response.path("photo_job"))
        .put("status", "fallback");
    responseOverride = response.toString();
    var incoming =
        verified(PHONE)
            .withVerifiedBlueBubblesInbound(
                List.of(new IncomingAttachment("own", "image/heic", "IMG.HEIC", null, null, null)));
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
    Path path = Files.createTempFile("gayodex-fallback-original-", ".bin");
    Files.write(path, bytes.toByteArray());
    when(bb.getAttachment("own")).thenReturn(path);
    var result =
        MAPPER.readTree(
            call(
                GayodexAgentTools.PHOTO, incoming, "{\"expected_revision\":\"" + REVISION + "\"}"));
    assertTrue(result.path("saved_to_draft").asBoolean());
    assertTrue(result.path("requires_owner_publish").asBoolean());
    assertEquals("fallback", result.path("photo_job").path("status").asText());
    assertFalse(result.path("photo_job").has("id"));
    assertTrue(
        result.path("photo_job").path("message").asText().contains("original photo was kept"));
    assertEquals(1, calls.size());
    assertEquals("/api/v1/bluechat/photos", calls.getFirst().path());
    assertFalse(Files.exists(path));
  }

  @Test
  void photoJobPollingReturnsLatestRevisionAndHonestTerminalStateWithoutPrivateMessages()
      throws Exception {
    for (String state :
        List.of("queued", "processing", "complete", "fallback", "superseded", "original")) {
      responseOverride =
          response(PHONE)
              .replace(REVISION, "b".repeat(64))
              .replace("\"queued\"", "\"" + state + "\"");
      var result =
          MAPPER.readTree(call(GayodexAgentTools.JOB, verified(PHONE), "{\"job_id\":\"job-123\"}"));
      assertEquals(state, result.path("photo_job").path("status").asText());
      assertEquals("b".repeat(64), result.path("revision").asText());
      assertTrue(result.path("requires_owner_publish").asBoolean());
      assertFalse(result.toString().contains("PRIVATE"));
      if (state.equals("complete"))
        assertTrue(
            result.path("photo_job").path("message").asText().contains("owner must publish"));
      if (state.equals("fallback"))
        assertTrue(
            result.path("photo_job").path("message").asText().contains("original photo was kept"));
    }
  }

  @Test
  void photoUsesOnlyCurrentSendersVerifiedAttachmentAndDefaultsToPixelArt() throws Exception {
    var incoming =
        verified(PHONE)
            .withVerifiedBlueBubblesInbound(
                List.of(
                    new IncomingAttachment(
                        "own", "image/png", null, "https://evil.test", null, "fake")));
    String arguments = "{\"expected_revision\":\"" + REVISION + "\",\"attachment_guid\":\"%s\"}";
    assertTrue(
        call(GayodexAgentTools.PHOTO, incoming, arguments.formatted("other-group-senders-photo"))
            .contains("invalid_attachment"));
    verifyNoInteractions(bb);
    var bytes = new ByteArrayOutputStream();
    ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
    Path path = Files.createTempFile("gayodex-test-photo", ".png");
    Files.write(path, bytes.toByteArray());
    when(bb.getAttachment("own")).thenReturn(path);
    String output = call(GayodexAgentTools.PHOTO, incoming, arguments.formatted("own"));
    assertFalse(Files.exists(path));
    assertTrue(MAPPER.readTree(output).path("saved_to_draft").asBoolean());
    assertEquals("/api/v1/bluechat/photos", calls.getLast().path());
    assertEquals(
        java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()),
        calls.getLast().body().path("image_base64").asText());
    assertTrue(calls.getLast().body().path("pixelify").asBoolean());
    assertFalse(output.contains("image_base64"));
    verify(bb).getAttachment("own");
    var job = MAPPER.readTree(call(GayodexAgentTools.JOB, incoming, "{\"job_id\":\"job-123\"}"));
    assertEquals("queued", job.path("photo_job").path("status").asText());
    assertEquals(REVISION, job.path("revision").asText());
    assertEquals("/api/v1/bluechat/photo-jobs/job-123", calls.getLast().path());
    int count = calls.size();
    assertTrue(
        call(GayodexAgentTools.JOB, incoming, "{\"job_id\":\"../../profiles\"}")
            .contains("invalid_request"));
    assertEquals(count, calls.size());
  }

  @Test
  void oversizeOrInvalidPhotosAreRemovedWithoutUpload() throws Exception {
    var incoming =
        verified(PHONE)
            .withVerifiedBlueBubblesInbound(
                List.of(new IncomingAttachment("own", "image/png", null, null, null, null)));
    for (byte[] bytes : List.of(new byte[6 * 1024 * 1024 + 1], new byte[] {1, 2, 3})) {
      Path path = Files.createTempFile("gayodex-test-photo", ".png");
      Files.write(path, bytes);
      when(bb.getAttachment("own")).thenReturn(path);
      assertTrue(
          call(
                  GayodexAgentTools.PHOTO,
                  incoming,
                  "{\"expected_revision\":\"" + REVISION + "\",\"attachment_guid\":\"own\"}")
              .contains("invalid_"));
      assertFalse(Files.exists(path));
    }
    assertTrue(calls.isEmpty());
  }

  @Test
  void unboundReadAndUnexpectedSaveResponsesNeverClaimPublicationOrSuccessfulSave()
      throws Exception {
    responseOverride = "{\"profile\":null,\"revision\":\"" + REVISION + "\"}";
    assertTrue(
        MAPPER
            .readTree(call(GayodexAgentTools.READ, verified(PHONE), "{}"))
            .path("profile")
            .isNull());
    assertTrue(
        call(
                GayodexAgentTools.UPDATE,
                verified(PHONE),
                "{\"expected_revision\":\"" + REVISION + "\",\"changes\":{\"bio\":\"Hello\"}}")
            .contains("unavailable"));
    responseOverride = "{";
    assertTrue(call(GayodexAgentTools.READ, verified(PHONE), "{}").contains("unavailable"));
  }

  @Test
  void redirectsOversizeResponsesAndConnectionLossFailWithoutRetryOrCredentialForwarding()
      throws Exception {
    status.set(302);
    assertTrue(call(GayodexAgentTools.READ, verified(PHONE), "{}").contains("unavailable"));
    assertEquals(1, calls.size());
    assertEquals("/api/v1/bluechat/profile", calls.getFirst().path());
    status.set(200);
    responseOverride = "{\"padding\":\"" + "x".repeat(40 * 1024) + "\"}";
    assertTrue(call(GayodexAgentTools.READ, verified(PHONE), "{}").contains("unavailable"));
    assertEquals(2, calls.size());
    abortResponse = true;
    String result =
        call(
            GayodexAgentTools.UPDATE,
            verified(PHONE),
            "{\"expected_revision\":\"" + REVISION + "\",\"changes\":{\"bio\":\"Hello\"}}");
    assertTrue(result.contains("unavailable"));
    assertFalse(result.contains(properties.getToken()));
    assertEquals(3, calls.size());
  }

  @Test
  void unlinkedTexterDiscoversAllFiveNativeToolsThroughRegistryAndLucene() throws Exception {
    var transport = mock(MessageTransport.class);
    when(transport.id()).thenReturn("bluebubbles");
    var transports = mock(MessageTransportRegistry.class);
    when(transports.resolve(any())).thenReturn(transport);
    var profile = mock(AgentProfileService.class);
    when(profile.resolveOrCreateAccountId(any())).thenReturn(Optional.empty());
    var registry =
        new AgentToolRegistry(
            bb,
            mock(HindsightClient.class),
            mock(GcalClient.class),
            null,
            mock(GiphyClient.class),
            transports,
            MAPPER,
            () -> null,
            null,
            null,
            mock(CadenceWorkflowLauncher.class),
            profile,
            null,
            null,
            null,
            null,
            null,
            tools);
    var incoming = verified(PHONE);
    for (String name : GayodexAgentTools.TOOL_NAMES) {
      assertNotNull(registry.resolveTool(name, incoming));
      assertEquals("gayodex", registry.toolCategory(name));
      assertNull(registry.resolveTool(name, incoming(PHONE)));
      assertFalse(
          MAPPER
              .writeValueAsString(registry.resolveTool(name, incoming).parameters())
              .contains(properties.getToken()));
    }
    AgentTool search = registry.resolveTool("toolSearchTool", incoming);
    var result =
        MAPPER.readTree(
            search
                .handler()
                .apply(
                    ToolContextFixture.with(incoming).objectMapper(MAPPER).build(),
                    MAPPER.readTree(
                        "{\"query\":\"Gayodex profile portrait photo entry\",\"maxResults\":10}")));
    assertTrue(
        new java.util.HashSet<>(MAPPER.convertValue(result, List.class))
            .containsAll(GayodexAgentTools.TOOL_NAMES));
    properties.setToken("");
    for (String name : GayodexAgentTools.TOOL_NAMES)
      assertNull(registry.resolveTool(name, incoming));
    assertFalse(
        registry.availableTools(incoming).stream()
            .anyMatch(t -> GayodexAgentTools.TOOL_NAMES.contains(t.name())));
    assertTrue(calls.isEmpty());
  }
}
