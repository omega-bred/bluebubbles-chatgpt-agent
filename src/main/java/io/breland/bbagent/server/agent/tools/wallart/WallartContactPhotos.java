package io.breland.bbagent.server.agent.tools.wallart;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.generated.bluebubblesclient.model.Contact;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.account.AgentAccountIdentifiers;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WallartContactPhotos {
  private final BBHttpClientWrapper blueBubbles;

  /** All addresses originate in fresh conversation metadata, never arbitrary tool arguments. */
  public List<Photo> resolve(IncomingMessage message) {
    if (message == null
        || !message.isBlueBubblesTransport()
        || StringUtils.isBlank(message.chatGuid())) {
      throw new IllegalArgumentException("Contact photos require a BlueBubbles conversation.");
    }
    JsonNode conversation = blueBubbles.getConversationInfoJson(message.chatGuid());
    JsonNode participants = conversation == null ? null : conversation.get("participants");
    if (participants == null || !participants.isArray() || participants.isEmpty()) {
      throw new IllegalArgumentException("Unable to verify this conversation's participants.");
    }
    List<String> addresses = new ArrayList<>();
    for (JsonNode participant : participants) {
      String address =
          StringUtils.firstNonBlank(
              participant.path("address").asText(null), participant.path("handle").asText(null));
      if (StringUtils.isBlank(address))
        throw new IllegalArgumentException("A participant has no usable contact address.");
      if (addresses.stream()
          .noneMatch(existing -> AgentAccountIdentifiers.equivalent(existing, address)))
        addresses.add(address);
    }
    if (addresses.size() > 32)
      throw new IllegalArgumentException(
          "This chat has too many participants to look up contact photos; attach selected photos instead.");
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    List<Photo> result = new ArrayList<>();
    long retainedBytes = 0;
    for (String address : addresses) {
      // If a phone and email match one verified contact, include that person once.
      if (result.stream().anyMatch(photo -> photo.matches(address))) continue;
      Photo photo = resolveOne(address, deadline);
      if (photo.image() != null) {
        if (retainedBytes + photo.image().bytes().length > WallartImageInputs.MAX_TOTAL_BYTES) {
          photo =
              new Photo(
                  photo.participant(),
                  photo.name(),
                  photo.aliases(),
                  null,
                  photo.source(),
                  "photo_budget_exceeded");
        } else retainedBytes += photo.image().bytes().length;
      }
      result.add(photo);
    }
    return result;
  }

  private Photo resolveOne(String address, long deadline) {
    String name = address;
    List<String> aliases = List.of(address);
    boolean lookupFailed = false;
    Photo unsupported = null;
    try {
      var contacts = blueBubbles.getContactPhotosForAddress(address, remaining(deadline));
      for (Contact contact : contacts) {
        var contactAddresses = addresses(contact);
        // BlueBubbles uses suffix matching; confirm the full normalized identifier ourselves.
        if (contactAddresses.stream()
            .noneMatch(candidate -> AgentAccountIdentifiers.equivalent(candidate, address)))
          continue;
        name =
            StringUtils.firstNonBlank(
                contact.getDisplayName(),
                contact.getNickname(),
                StringUtils.trimToNull(
                    StringUtils.defaultString(contact.getFirstName())
                        + " "
                        + StringUtils.defaultString(contact.getLastName())),
                address);
        aliases = contactAddresses;
        if (StringUtils.isNotBlank(contact.getAvatar())) {
          Photo saved = photo(address, name, aliases, contact.getAvatar(), "contacts");
          if (saved.image() != null) return saved;
          unsupported = saved;
        }
      }
    } catch (RuntimeException ignored) {
      lookupFailed = true;
    }
    try {
      var shared = blueBubbles.getSharedContactPhoto(address, remaining(deadline));
      if (shared != null && StringUtils.isNotBlank(shared.path("avatar").asText(null))) {
        return photo(
            address,
            StringUtils.firstNonBlank(shared.path("name").asText(null), name),
            aliases,
            shared.path("avatar").asText(),
            "shared_profile");
      }
    } catch (RuntimeException ignored) {
      // Private API or profile sharing may be unavailable. A saved photo still works without it.
    }
    if (unsupported != null) return unsupported;
    return new Photo(
        address, name, aliases, null, null, lookupFailed ? "lookup_failed" : "missing_photo");
  }

  private Photo photo(
      String address, String name, List<String> aliases, String data, String source) {
    try {
      var image = WallartImageInputs.decodeContact(data);
      return new Photo(address, name, aliases, image, source, "available");
    } catch (IllegalArgumentException ignored) {
      return new Photo(address, name, aliases, null, source, "unsupported_photo");
    }
  }

  private static List<String> addresses(Contact contact) {
    if (contact == null) return List.of();
    List<String> addresses = new ArrayList<>();
    if (contact.getPhoneNumbers() != null)
      contact
          .getPhoneNumbers()
          .forEach(
              entry -> {
                if (entry != null && StringUtils.isNotBlank(entry.getAddress()))
                  addresses.add(entry.getAddress());
              });
    if (contact.getEmails() != null)
      contact
          .getEmails()
          .forEach(
              entry -> {
                if (entry != null && StringUtils.isNotBlank(entry.getAddress()))
                  addresses.add(entry.getAddress());
              });
    return addresses;
  }

  private static Duration remaining(long deadline) {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) throw new IllegalStateException("Contact photo lookup timed out");
    return Duration.ofNanos(nanos);
  }

  public record Photo(
      String participant,
      String name,
      List<String> aliases,
      WallartImageInputs.ImageData image,
      String source,
      String status) {
    boolean matches(String address) {
      return aliases.stream().anyMatch(alias -> AgentAccountIdentifiers.equivalent(alias, address));
    }

    public Map<String, Object> summary() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("participant", participant);
      result.put("name", name);
      result.put("status", status);
      if (source != null) result.put("source", source);
      return result;
    }

    @Override
    public String toString() {
      return summary().toString();
    }
  }
}
