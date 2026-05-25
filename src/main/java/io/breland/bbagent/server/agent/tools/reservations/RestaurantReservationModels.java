package io.breland.bbagent.server.agent.tools.reservations;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public final class RestaurantReservationModels {
  private RestaurantReservationModels() {}

  public record ProviderCapability(
      String provider,
      boolean apiConfigured,
      boolean restaurantSearchApiConfigured,
      boolean availabilityApiSupported,
      boolean bookingApiSupported,
      boolean directLoginSupported,
      String loginUrl,
      String bookingCompletion,
      List<String> notes) {}

  public record CapabilityResponse(String status, List<ProviderCapability> providers) {}

  public record ProviderLink(String provider, String label, String url) {}

  public record RestaurantSearchResult(
      String provider,
      String restaurantId,
      String name,
      String address,
      String city,
      String state,
      String country,
      String phone,
      String reservationUrl,
      String source) {}

  @Schema(description = "Restaurant reservation search request.")
  public record SearchRequest(
      @Schema(
              description = "Restaurant, cuisine, neighborhood, or free-text search query.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String query,
      @Schema(description = "City, neighborhood, address, or other location hint.") String location,
      @Schema(description = "Reservation provider to search: all, opentable, or resy.")
          String provider,
      @Schema(description = "Desired reservation date/time as local or ISO-8601 text.")
          String dateTime,
      @Schema(description = "Party size.") Integer partySize,
      @Schema(description = "Maximum API-backed restaurant results to return.") Integer limit) {}

  public record SearchResponse(
      String status,
      List<RestaurantSearchResult> results,
      List<ProviderLink> links,
      List<String> notes,
      CapabilityResponse capabilities) {}

  @Schema(description = "Restaurant availability lookup request.")
  public record AvailabilityRequest(
      @Schema(
              description = "Reservation provider: opentable or resy.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String provider,
      @Schema(description = "Provider restaurant id, such as an OpenTable rid.")
          String restaurantId,
      @Schema(description = "Restaurant name for handoff links when provider id is unknown.")
          String restaurantName,
      @Schema(description = "Restaurant city or location hint for handoff links.") String location,
      @Schema(
              description = "Desired reservation date/time as local or ISO-8601 text.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String dateTime,
      @Schema(description = "Party size.", requiredMode = Schema.RequiredMode.REQUIRED)
          Integer partySize,
      @Schema(description = "Minutes before dateTime to search.") Integer backwardMinutes,
      @Schema(description = "Minutes after dateTime to search.") Integer forwardMinutes,
      @Schema(description = "Preferred table type, such as standard, bar, outdoor, or high_top.")
          String seatingPreference,
      @Schema(description = "Include availability that may require a credit card or prepayment.")
          Boolean includeCreditCardResults) {}

  public record AvailabilitySlot(
      String dateTime,
      String bookingUrl,
      String bookingRestrefUrl,
      String reservationAttribute,
      String environment,
      Integer diningAreaId,
      List<String> attributes,
      List<Integer> experienceIds,
      Boolean creditCardRequired,
      String depositType,
      String cancellationPolicy) {}

  public record AvailabilityResponse(
      String status,
      String provider,
      String restaurantId,
      String restaurantName,
      String dateTime,
      Integer partySize,
      List<AvailabilitySlot> slots,
      List<String> noAvailabilityReasons,
      List<ProviderLink> links,
      List<String> notes) {}

  @Schema(description = "Restaurant reservation booking request.")
  public record BookingRequest(
      @Schema(
              description = "Reservation provider: opentable or resy.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String provider,
      @Schema(description = "Provider restaurant id, such as an OpenTable rid.")
          String restaurantId,
      @Schema(description = "Restaurant name for handoff links and user-facing context.")
          String restaurantName,
      @Schema(description = "Restaurant city or location hint for handoff links.") String location,
      @Schema(
              description = "Desired reservation date/time as local or ISO-8601 text.",
              requiredMode = Schema.RequiredMode.REQUIRED)
          String dateTime,
      @Schema(description = "Party size.", requiredMode = Schema.RequiredMode.REQUIRED)
          Integer partySize,
      @Schema(description = "First name for the reservation.") String firstName,
      @Schema(description = "Last name for the reservation.") String lastName,
      @Schema(description = "Email address for the reservation.") String email,
      @Schema(description = "Phone country code, such as 1 for US/Canada.") String phoneCountryCode,
      @Schema(description = "Phone number for the reservation.") String phoneNumber,
      @Schema(description = "Phone type, such as mobile, home, or work.") String phoneType,
      @Schema(description = "Preferred table type, such as standard, bar, outdoor, or high_top.")
          String seatingPreference,
      @Schema(description = "OpenTable dining area id from an availability slot.")
          Integer diningAreaId,
      @Schema(description = "OpenTable environment from an availability slot.") String environment,
      @Schema(description = "OpenTable reservation attribute from an availability slot.")
          String reservationAttribute,
      @Schema(description = "Optional reservation token from a prior slot lock.")
          String reservationToken,
      @Schema(description = "Optional special request. Do not include payment card data.")
          String specialRequest,
      @Schema(description = "True only after the user explicitly confirmed the exact booking.")
          Boolean confirmedByUser,
      @Schema(description = "True only after the user explicitly accepted the provider terms.")
          Boolean acceptedProviderTerms) {}

  public record BookingResponse(
      String status,
      String provider,
      String restaurantId,
      String restaurantName,
      String confirmationNumber,
      String reservationDateTime,
      Integer partySize,
      String manageReservationUrl,
      String slotLockExpiresAt,
      String message,
      List<ProviderLink> links,
      List<String> notes) {}
}
