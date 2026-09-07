package io.breland.bbagent.server.agent.tools.wallart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.breland.bbagent.generated.bluebubblesclient.model.Contact;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.cadence.models.IncomingAttachment;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class WallartContactPhotosTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final BBHttpClientWrapper bb = mock(BBHttpClientWrapper.class);
  private final WallartMcpClient client = mock(WallartMcpClient.class);
  private final WallartContactPhotos photos = new WallartContactPhotos(bb);
  private final WallartImageInputs inputs = new WallartImageInputs(bb, client, mapper, photos);

  @Test
  void savedPhotosDeduplicateVerifiedPhoneAndEmailWithoutExposingBytes() throws Exception {
    participants("+14155550101", "alice@example.com", "bob@example.com");
    String data = WallartImageInputsTest.png();
    when(bb.getContactPhotosForAddress(eq("+14155550101"), any()))
        .thenReturn(List.of(contact("Alice", data, "+1 (415) 555-0101", "alice@example.com")));
    saved("bob@example.com", "Bob", data);
    var resolved = photos.resolve(message());
    assertEquals(
        List.of("Alice", "Bob"), resolved.stream().map(WallartContactPhotos.Photo::name).toList());
    assertTrue(resolved.stream().allMatch(p -> p.status().equals("available")));
    assertFalse(
        mapper
            .writeValueAsString(resolved.stream().map(WallartContactPhotos.Photo::summary).toList())
            .contains(data));
    assertFalse(resolved.toString().contains(data));
    verify(bb, never()).getContactPhotosForAddress(eq("alice@example.com"), any());
    verify(bb, never()).getSharedContactPhoto(anyString(), any());
  }

  @Test
  void fallsBackToSharedProfileWhenSavedPhotoIsMissingOrUnsupported() throws Exception {
    participants("alice@example.com");
    saved("alice@example.com", "Alice", "not-base64!");
    when(bb.getSharedContactPhoto(eq("alice@example.com"), any()))
        .thenReturn(
            mapper.valueToTree(
                Map.of("name", "Shared Alice", "avatar", WallartImageInputsTest.png())));
    var photo = photos.resolve(message()).getFirst();
    assertEquals("available", photo.status());
    assertEquals("shared_profile", photo.source());
    assertEquals("Shared Alice", photo.name());
  }

  @Test
  void rejectsBlueBubblesSuffixOnlyContactMatches() throws Exception {
    participants("alice@example.com");
    when(bb.getContactPhotosForAddress(eq("alice@example.com"), any()))
        .thenReturn(
            List.of(
                contact("Wrong person", WallartImageInputsTest.png(), "not-alice@example.com")));
    assertEquals("missing_photo", photos.resolve(message()).getFirst().status());
    verify(bb).getSharedContactPhoto(eq("alice@example.com"), any());
  }

  @Test
  void requiresFreshVerifiableMembershipAndNeverQueriesOutsideChat() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> photos.resolve(message()));
    participants("alice@example.com");
    saved("alice@example.com", "Alice", WallartImageInputsTest.png());
    assertThrows(
        IllegalArgumentException.class,
        () -> inputs.resolve(message(), List.of(ref("contact_photo", "outside@example.com"))));
    verify(bb, never()).getContactPhotosForAddress(eq("outside@example.com"), any());
    when(bb.getConversationInfoJson("chat")).thenReturn(mapper.readTree("{\"participants\":[]}"));
    assertThrows(
        IllegalArgumentException.class,
        () -> inputs.resolve(message(), List.of(ref("contact_photo", "alice@example.com"))));
    verifyNoInteractions(client);
  }

  @Test
  void missingPhotosBlockEveryoneButAllowExplicitSelection() throws Exception {
    participants("alice@example.com", "bob@example.com");
    saved("alice@example.com", "Alice", WallartImageInputsTest.png());
    saved("bob@example.com", "Bob", null);
    when(bb.getSharedContactPhoto(eq("bob@example.com"), any()))
        .thenThrow(new IllegalStateException("Private API disabled"));
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> inputs.resolve(message(), List.of(ref("all_contact_photos", null))));
    assertTrue(error.getMessage().contains("Bob (missing_photo)"));
    assertTrue(error.getMessage().contains("explicit instructions to omit"));
    assertEquals(
        "Alice",
        inputs
            .resolve(message(), List.of(ref("contact_photo", "alice@example.com")))
            .getFirst()
            .get("description"));
    verifyNoInteractions(client);
  }

  @Test
  void expandsAllContactsInOrderAlongsideAnAttachment() throws Exception {
    participants("alice@example.com", "bob@example.com");
    String data = WallartImageInputsTest.png();
    saved("alice@example.com", "Alice", data);
    saved("bob@example.com", "Bob", data);
    var result =
        inputs.resolve(
            message(),
            List.of(
                new WallartImageInputs.ImageReference("attachment", 1, null, "scene", null),
                ref("all_contact_photos", null)));
    assertEquals(
        List.of("scene", "Alice", "Bob"), result.stream().map(i -> i.get("description")).toList());
    assertTrue(result.stream().allMatch(i -> data.equals(i.get("data"))));
  }

  @Test
  void rejectsMoreThanSixteenExpandedImages() throws Exception {
    var addresses =
        java.util.stream.IntStream.range(0, 17)
            .mapToObj(i -> "person" + i + "@example.com")
            .toArray(String[]::new);
    participants(addresses);
    for (String address : addresses) saved(address, address, WallartImageInputsTest.png());
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> inputs.resolve(message(), List.of(ref("all_contact_photos", null))));
    assertTrue(error.getMessage().contains("1 to 16"));
    verifyNoInteractions(client);
  }

  @Test
  void reportsLookupFailureWithoutLeakingRemoteError() throws Exception {
    participants("alice@example.com");
    when(bb.getContactPhotosForAddress(anyString(), any()))
        .thenThrow(new IllegalStateException("private response"));
    when(bb.getSharedContactPhoto(anyString(), any()))
        .thenThrow(new IllegalStateException("private response"));
    assertEquals("lookup_failed", photos.resolve(message()).getFirst().status());
    assertFalse(photos.resolve(message()).toString().contains("private response"));
  }

  @Test
  void normalizesNativeTiffContactPhotosToPng() throws Exception {
    participants("alice@example.com");
    var encoded = new ByteArrayOutputStream();
    assertTrue(ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "tiff", encoded));
    saved("alice@example.com", "Alice", Base64.getEncoder().encodeToString(encoded.toByteArray()));
    var photo = photos.resolve(message()).getFirst();
    assertEquals("image/png", photo.image().mimeType());
    assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(photo.image().bytes())));
  }

  @Test
  void boundsRetainedPhotoBytesAndReportsAnyoneOverBudget() throws Exception {
    participants("alice@example.com", "bob@example.com", "charlie@example.com");
    byte[] padded =
        java.util.Arrays.copyOf(
            Base64.getDecoder().decode(WallartImageInputsTest.png()), 8 * 1024 * 1024);
    String data = Base64.getEncoder().encodeToString(padded);
    for (String name : List.of("alice", "bob", "charlie")) saved(name + "@example.com", name, data);
    var result = photos.resolve(message());
    assertEquals("photo_budget_exceeded", result.get(2).status());
    assertNull(result.get(2).image());
    assertTrue(
        result.stream()
                .filter(p -> p.image() != null)
                .mapToLong(p -> p.image().bytes().length)
                .sum()
            <= WallartImageInputs.MAX_TOTAL_BYTES);
  }

  private void participants(String... addresses) {
    when(bb.getConversationInfoJson("chat"))
        .thenReturn(
            mapper.valueToTree(
                Map.of(
                    "participants",
                    java.util.Arrays.stream(addresses).map(a -> Map.of("address", a)).toList())));
  }

  private void saved(String address, String name, String data) throws Exception {
    when(bb.getContactPhotosForAddress(eq(address), any()))
        .thenReturn(List.of(contact(name, data, address)));
  }

  private Contact contact(String name, String data, String... addresses) {
    var node = mapper.createObjectNode().put("displayName", name).put("avatar", data);
    var phones = node.putArray("phoneNumbers");
    var emails = node.putArray("emails");
    for (String address : addresses)
      (address.contains("@") ? emails : phones).addObject().put("address", address);
    return mapper.convertValue(node, Contact.class);
  }

  private WallartImageInputs.ImageReference ref(String source, String participant) {
    return new WallartImageInputs.ImageReference(source, null, null, null, participant);
  }

  private IncomingMessage message() throws Exception {
    return new IncomingMessage(
        "chat",
        "message",
        null,
        "compose",
        false,
        "iMessage",
        "alice@example.com",
        false,
        Instant.EPOCH,
        List.of(
            new IncomingAttachment(
                null, "image/png", "scene.png", null, null, WallartImageInputsTest.png())),
        false);
  }
}
