package io.breland.bbagent.server.agent.location;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record ReverseLocationLookupResult(String displayName, Map<String, String> address) {
  public String approximateAddress() {
    if (displayName != null && !displayName.isBlank()) {
      return displayName;
    }
    if (address == null || address.isEmpty()) {
      return null;
    }

    List<String> parts = new ArrayList<>();
    String street = streetAddress();
    addIfPresent(parts, street);
    addFirstPresent(parts, "neighbourhood", "suburb", "quarter");
    addFirstPresent(parts, "city", "town", "village", "hamlet", "municipality", "county");
    addFirstPresent(parts, "state");
    addFirstPresent(parts, "postcode");
    addFirstPresent(parts, "country");
    return parts.isEmpty() ? null : String.join(", ", parts);
  }

  private String streetAddress() {
    String road = firstPresent("road", "pedestrian", "footway", "path");
    String houseNumber = firstPresent("house_number");
    if (road == null) {
      return houseNumber;
    }
    if (houseNumber == null) {
      return road;
    }
    return houseNumber + " " + road;
  }

  private void addFirstPresent(List<String> parts, String... keys) {
    addIfPresent(parts, firstPresent(keys));
  }

  private String firstPresent(String... keys) {
    for (String key : keys) {
      String value = address.get(key);
      if (value != null && !value.isBlank()) {
        return value;
      }
    }
    return null;
  }

  private static void addIfPresent(List<String> parts, String value) {
    if (value != null && !value.isBlank() && !parts.contains(value)) {
      parts.add(value);
    }
  }
}
