package io.breland.bbagent.server.agent.tools.reservations;

import com.fasterxml.jackson.databind.JsonNode;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.AvailabilityRequest;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.AvailabilityResponse;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.AvailabilitySlot;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.BookingRequest;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.BookingResponse;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.CapabilityResponse;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.ProviderCapability;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.ProviderLink;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.RestaurantSearchResult;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.SearchRequest;
import io.breland.bbagent.server.agent.tools.reservations.RestaurantReservationModels.SearchResponse;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

@Component
@Slf4j
public class RestaurantReservationService implements RestaurantReservationGateway {
  private static final String PROVIDER_OPENTABLE = "opentable";
  private static final String PROVIDER_RESY = "resy";
  private static final String PROVIDER_ALL = "all";
  private static final int DEFAULT_SEARCH_LIMIT = 5;
  private static final int MAX_SEARCH_LIMIT = 20;

  private final RestaurantReservationProperties properties;
  private final WebClient openTableWebClient;
  private final WebClient openTableOauthClient;
  private volatile CachedToken openTableToken;

  public RestaurantReservationService(RestaurantReservationProperties properties) {
    this.properties = properties == null ? new RestaurantReservationProperties() : properties;
    this.openTableWebClient = WebClient.builder().baseUrl(openTableBaseUrl()).build();
    this.openTableOauthClient = WebClient.builder().baseUrl(openTableOauthUrl()).build();
  }

  @Override
  public CapabilityResponse capabilities() {
    boolean openTableApiConfigured = openTableApiConfigured();
    boolean openTableSearchConfigured = openTableSearchConfigured();
    return new CapabilityResponse(
        "ok",
        List.of(
            new ProviderCapability(
                PROVIDER_OPENTABLE,
                openTableApiConfigured,
                openTableSearchConfigured,
                openTableApiConfigured,
                openTableApiConfigured,
                false,
                "https://www.opentable.com/login",
                openTableApiConfigured
                    ? "Direct API-backed booking is available after explicit user confirmation and provider terms acceptance."
                    : "OpenTable web handoff links are available until partner API credentials are configured.",
                List.of(
                    "OpenTable partner API access uses app credentials, not a user's consumer password.",
                    "Do not ask for or store raw payment card data in chat.")),
            new ProviderCapability(
                PROVIDER_RESY,
                false,
                false,
                false,
                false,
                false,
                resyLoginUrl(),
                "Resy booking and login complete on Resy's website.",
                List.of(
                    "No public Resy booking API is configured for this app.",
                    "Use Resy handoff links for login and final booking."))));
  }

  @Override
  public SearchResponse searchRestaurants(SearchRequest request) {
    String query = StringUtils.trimToNull(request == null ? null : request.query());
    String location = StringUtils.trimToNull(request == null ? null : request.location());
    String provider = normalizeProvider(request == null ? null : request.provider(), true);
    int limit = safeLimit(request == null ? null : request.limit());
    if (query == null && location == null) {
      return new SearchResponse(
          "validation_error",
          List.of(),
          List.of(),
          List.of("Provide at least a restaurant/cuisine query or a location."),
          capabilities());
    }

    List<RestaurantSearchResult> results = new ArrayList<>();
    List<ProviderLink> links = new ArrayList<>();
    List<String> notes = new ArrayList<>();

    if (includesProvider(provider, PROVIDER_OPENTABLE)) {
      if (openTableSearchConfigured()) {
        try {
          results.addAll(searchOpenTableDirectory(request, limit));
        } catch (RuntimeException e) {
          log.warn("OpenTable directory search failed", e);
          notes.add("OpenTable API directory search failed: " + compactError(e));
          links.add(openTableSearchLink(query, location, request.dateTime(), request.partySize()));
        }
      } else {
        notes.add(
            "OpenTable directory API search is not configured; returning OpenTable web search link.");
        links.add(openTableSearchLink(query, location, request.dateTime(), request.partySize()));
      }
    }
    if (includesProvider(provider, PROVIDER_RESY)) {
      notes.add("Resy direct API search is not configured; returning Resy web search link.");
      links.add(resySearchLink(query, location, request.dateTime(), request.partySize()));
    }

    String status = results.isEmpty() && links.isEmpty() ? "no_results" : "ok";
    return new SearchResponse(status, results, links, notes, capabilities());
  }

  @Override
  public AvailabilityResponse checkAvailability(AvailabilityRequest request) {
    if (request == null) {
      return availabilityValidationError(null, null, "Missing availability request.");
    }
    String provider = normalizeProvider(request.provider(), false);
    if (PROVIDER_RESY.equals(provider)) {
      return handoffAvailability(
          PROVIDER_RESY,
          request.restaurantId(),
          request.restaurantName(),
          request.location(),
          request.dateTime(),
          request.partySize(),
          "Resy direct availability API is not configured.");
    }
    if (!PROVIDER_OPENTABLE.equals(provider)) {
      return availabilityValidationError(
          provider, request.restaurantId(), "Provider must be opentable or resy.");
    }
    if (StringUtils.isBlank(request.restaurantId())) {
      return handoffAvailability(
          PROVIDER_OPENTABLE,
          null,
          request.restaurantName(),
          request.location(),
          request.dateTime(),
          request.partySize(),
          "OpenTable availability requires an OpenTable rid.");
    }
    if (StringUtils.isBlank(request.dateTime()) || request.partySize() == null) {
      return availabilityValidationError(
          PROVIDER_OPENTABLE,
          request.restaurantId(),
          "dateTime and partySize are required for availability lookup.");
    }
    if (!openTableApiConfigured()) {
      return handoffAvailability(
          PROVIDER_OPENTABLE,
          request.restaurantId(),
          request.restaurantName(),
          request.location(),
          request.dateTime(),
          request.partySize(),
          "OpenTable API credentials are not configured.");
    }

    try {
      JsonNode response = authorizedOpenTableGet(buildOpenTableAvailabilityPath(request));
      List<AvailabilitySlot> slots = parseAvailabilitySlots(response);
      List<String> noAvailabilityReasons = parseNoAvailabilityReasons(response);
      List<String> notes = new ArrayList<>();
      if (slots.isEmpty()) {
        notes.add("No OpenTable availability slots were returned for the requested window.");
      }
      return new AvailabilityResponse(
          slots.isEmpty() ? "no_availability" : "ok",
          PROVIDER_OPENTABLE,
          request.restaurantId(),
          request.restaurantName(),
          request.dateTime(),
          request.partySize(),
          slots,
          noAvailabilityReasons,
          List.of(
              openTableSearchLink(
                  request.restaurantName(),
                  request.location(),
                  request.dateTime(),
                  request.partySize())),
          notes);
    } catch (RuntimeException e) {
      log.warn("OpenTable availability lookup failed", e);
      return new AvailabilityResponse(
          "error",
          PROVIDER_OPENTABLE,
          request.restaurantId(),
          request.restaurantName(),
          request.dateTime(),
          request.partySize(),
          List.of(),
          List.of(),
          List.of(
              openTableSearchLink(
                  request.restaurantName(),
                  request.location(),
                  request.dateTime(),
                  request.partySize())),
          List.of("OpenTable availability lookup failed: " + compactError(e)));
    }
  }

  @Override
  public BookingResponse makeReservation(BookingRequest request) {
    if (request == null) {
      return bookingValidationError(null, null, "Missing booking request.");
    }
    String provider = normalizeProvider(request.provider(), false);
    if (PROVIDER_RESY.equals(provider)) {
      return handoffBooking(PROVIDER_RESY, request, "Resy direct booking API is not configured.");
    }
    if (!PROVIDER_OPENTABLE.equals(provider)) {
      return bookingValidationError(
          provider, request.restaurantId(), "Provider must be opentable or resy.");
    }
    if (!Boolean.TRUE.equals(request.confirmedByUser())
        || !Boolean.TRUE.equals(request.acceptedProviderTerms())) {
      return new BookingResponse(
          "requires_confirmation",
          PROVIDER_OPENTABLE,
          request.restaurantId(),
          request.restaurantName(),
          null,
          request.dateTime(),
          request.partySize(),
          null,
          null,
          "Ask the user to explicitly confirm the exact restaurant, date/time, party size, guest details, and acceptance of OpenTable terms before booking.",
          List.of(
              openTableSearchLink(
                  request.restaurantName(),
                  request.location(),
                  request.dateTime(),
                  request.partySize())),
          List.of(
              "Making a reservation is an external commitment.",
              "Do not collect raw payment-card data in chat."));
    }
    List<String> missing = missingOpenTableBookingFields(request);
    if (!missing.isEmpty()) {
      return bookingValidationError(
          PROVIDER_OPENTABLE,
          request.restaurantId(),
          "Missing required booking fields: " + String.join(", ", missing) + ".");
    }
    if (!openTableApiConfigured()) {
      return handoffBooking(
          PROVIDER_OPENTABLE, request, "OpenTable API credentials are not configured.");
    }

    try {
      SlotLock slotLock =
          StringUtils.isBlank(request.reservationToken()) ? lockOpenTableSlot(request) : null;
      String reservationToken =
          slotLock == null ? StringUtils.trimToNull(request.reservationToken()) : slotLock.token();
      if (reservationToken == null) {
        return bookingValidationError(
            PROVIDER_OPENTABLE,
            request.restaurantId(),
            "OpenTable did not return a reservation token for the requested slot.");
      }
      JsonNode response = createOpenTableReservation(request, reservationToken);
      return parseOpenTableBookingResponse(request, response, slotLock);
    } catch (RuntimeException e) {
      log.warn("OpenTable booking failed", e);
      return new BookingResponse(
          "error",
          PROVIDER_OPENTABLE,
          request.restaurantId(),
          request.restaurantName(),
          null,
          request.dateTime(),
          request.partySize(),
          null,
          null,
          "OpenTable booking failed: " + compactError(e),
          List.of(
              openTableSearchLink(
                  request.restaurantName(),
                  request.location(),
                  request.dateTime(),
                  request.partySize())),
          List.of("The user can complete booking through the OpenTable link if needed."));
    }
  }

  private List<RestaurantSearchResult> searchOpenTableDirectory(SearchRequest request, int limit) {
    String template = properties.getOpentable().getDirectorySearchUrlTemplate();
    String uri = expandSearchTemplate(template, request, limit);
    JsonNode response = authorizedOpenTableGet(uri);
    JsonNode resultsNode = firstArray(response, "restaurants", "results", "items", "data");
    if (resultsNode == null || !resultsNode.isArray()) {
      return List.of();
    }
    List<RestaurantSearchResult> results = new ArrayList<>();
    for (JsonNode item : resultsNode) {
      if (results.size() >= limit) {
        break;
      }
      RestaurantSearchResult result = parseOpenTableSearchResult(item);
      if (StringUtils.isNotBlank(result.name()) || StringUtils.isNotBlank(result.restaurantId())) {
        results.add(result);
      }
    }
    return results;
  }

  private RestaurantSearchResult parseOpenTableSearchResult(JsonNode item) {
    JsonNode restaurant =
        item.has("restaurant") && item.path("restaurant").isObject()
            ? item.path("restaurant")
            : item;
    JsonNode addressNode = restaurant.path("address");
    String address =
        firstText(
            restaurant,
            "address",
            "street_address",
            "streetAddress",
            "address1",
            "line1",
            "formatted_address");
    if (StringUtils.isBlank(address) && addressNode.isObject()) {
      address = firstText(addressNode, "line1", "street_address", "streetAddress", "formatted");
    }
    String city = firstText(restaurant, "city", "locality");
    if (StringUtils.isBlank(city) && addressNode.isObject()) {
      city = firstText(addressNode, "city", "locality");
    }
    String state = firstText(restaurant, "state", "province", "region");
    if (StringUtils.isBlank(state) && addressNode.isObject()) {
      state = firstText(addressNode, "state", "province", "region");
    }
    String country = firstText(restaurant, "country", "country_code", "countryCode");
    if (StringUtils.isBlank(country) && addressNode.isObject()) {
      country = firstText(addressNode, "country", "country_code", "countryCode");
    }
    return new RestaurantSearchResult(
        PROVIDER_OPENTABLE,
        firstText(restaurant, "rid", "restaurant_id", "restaurantId", "id"),
        firstText(restaurant, "name", "restaurant_name", "restaurantName"),
        address,
        city,
        state,
        country,
        firstText(restaurant, "phone", "phone_number", "phoneNumber"),
        firstText(
            restaurant, "reservation_url", "reservationUrl", "booking_url", "bookingUrl", "url"),
        "opentable_api");
  }

  private String buildOpenTableAvailabilityPath(AvailabilityRequest request) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromPath(
                "/v2/availability/"
                    + UriUtils.encodePathSegment(request.restaurantId(), StandardCharsets.UTF_8))
            .queryParam("start_date_time", request.dateTime())
            .queryParam("party_size", request.partySize())
            .queryParam(
                "backward_minutes",
                defaulted(
                    request.backwardMinutes(),
                    properties.getOpentable().getDefaultBackwardMinutes()))
            .queryParam(
                "forward_minutes",
                defaulted(
                    request.forwardMinutes(),
                    properties.getOpentable().getDefaultForwardMinutes()));
    if (request.includeCreditCardResults() != null) {
      builder.queryParam("include_credit_card_results", request.includeCreditCardResults());
    }
    if (StringUtils.isNotBlank(request.seatingPreference())) {
      builder.queryParam("reservation_attribute", request.seatingPreference().trim());
    }
    return builder.toUriString();
  }

  private SlotLock lockOpenTableSlot(BookingRequest request) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("date_time", request.dateTime());
    body.put("party_size", request.partySize());
    addIfPresent(body, "reservation_attribute", request.reservationAttribute());
    if (StringUtils.isBlank(request.reservationAttribute())) {
      addIfPresent(body, "reservation_attribute", request.seatingPreference());
    }
    addIfPresent(body, "dining_area_id", request.diningAreaId());
    addIfPresent(body, "environment", request.environment());
    addIfPresent(body, "referral_id", properties.getOpentable().getReferralId());
    JsonNode response =
        authorizedOpenTablePost(
            "/v2/booking/"
                + UriUtils.encodePathSegment(request.restaurantId(), StandardCharsets.UTF_8)
                + "/slot_locks",
            body);
    String token =
        firstText(response, "reservation_token", "reservationToken", "token", "slot_lock_token");
    String expiresAt = firstText(response, "expires_at", "expiresAt", "expiration", "expires");
    return new SlotLock(token, expiresAt);
  }

  private JsonNode createOpenTableReservation(BookingRequest request, String reservationToken) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reservation_token", reservationToken);
    body.put("first_name", request.firstName().trim());
    body.put("last_name", request.lastName().trim());
    body.put("email_address", request.email().trim());
    body.put("phone", phoneBody(request));
    addIfPresent(body, "special_request", truncateSpecialRequest(request.specialRequest()));
    addIfPresent(body, "reservation_attribute", request.reservationAttribute());
    if (StringUtils.isBlank(request.reservationAttribute())) {
      addIfPresent(body, "reservation_attribute", request.seatingPreference());
    }
    addIfPresent(body, "referral_id", properties.getOpentable().getReferralId());
    body.put("restaurant_email_marketing_opt_in", false);
    body.put("opentable_marketing_opt_in", false);
    return authorizedOpenTablePost(
        "/v2/booking/"
            + UriUtils.encodePathSegment(request.restaurantId(), StandardCharsets.UTF_8)
            + "/reservations",
        body);
  }

  private BookingResponse parseOpenTableBookingResponse(
      BookingRequest request, JsonNode response, @Nullable SlotLock slotLock) {
    String confirmation =
        firstText(
            response,
            "confirmation_number",
            "confirmationNumber",
            "reservation_id",
            "reservationId",
            "id");
    String manageUrl =
        firstText(
            response,
            "manage_reservation_url",
            "manageReservationUrl",
            "reservation_url",
            "reservationUrl",
            "booking_url",
            "url");
    String reservationDateTime =
        firstText(
            response, "date_time", "dateTime", "reservation_date_time", "reservationDateTime");
    if (StringUtils.isBlank(reservationDateTime)) {
      reservationDateTime = request.dateTime();
    }
    return new BookingResponse(
        "confirmed",
        PROVIDER_OPENTABLE,
        request.restaurantId(),
        request.restaurantName(),
        confirmation,
        reservationDateTime,
        request.partySize(),
        manageUrl,
        slotLock == null ? null : slotLock.expiresAt(),
        "OpenTable reservation created.",
        manageUrl == null || manageUrl.isBlank()
            ? List.of(
                openTableSearchLink(
                    request.restaurantName(),
                    request.location(),
                    request.dateTime(),
                    request.partySize()))
            : List.of(new ProviderLink(PROVIDER_OPENTABLE, "Manage reservation", manageUrl)),
        List.of());
  }

  private Map<String, Object> phoneBody(BookingRequest request) {
    Map<String, Object> phone = new LinkedHashMap<>();
    phone.put("number", request.phoneNumber().trim());
    phone.put(
        "country_code",
        StringUtils.defaultIfBlank(StringUtils.trimToNull(request.phoneCountryCode()), "1"));
    phone.put(
        "phone_type",
        StringUtils.defaultIfBlank(StringUtils.trimToNull(request.phoneType()), "mobile"));
    return phone;
  }

  private JsonNode authorizedOpenTableGet(String uri) {
    return openTableGet(uri)
        .headers(this::addOpenTableAuthorization)
        .accept(MediaType.APPLICATION_JSON)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block(openTableTimeout());
  }

  private JsonNode authorizedOpenTablePost(String uri, Map<String, Object> body) {
    return openTablePost(uri)
        .headers(this::addOpenTableAuthorization)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .retrieve()
        .bodyToMono(JsonNode.class)
        .block(openTableTimeout());
  }

  private WebClient.RequestHeadersSpec<?> openTableGet(String uri) {
    WebClient.RequestHeadersUriSpec<?> request = openTableWebClient.get();
    if (absoluteUri(uri)) {
      return request.uri(URI.create(uri));
    }
    return request.uri(relativeOpenTableUri(uri));
  }

  private WebClient.RequestBodySpec openTablePost(String uri) {
    WebClient.RequestBodyUriSpec request = openTableWebClient.post();
    if (absoluteUri(uri)) {
      return request.uri(URI.create(uri));
    }
    return request.uri(relativeOpenTableUri(uri));
  }

  private boolean absoluteUri(String uri) {
    return StringUtils.startsWithIgnoreCase(uri, "http://")
        || StringUtils.startsWithIgnoreCase(uri, "https://");
  }

  private String relativeOpenTableUri(String uri) {
    return uri.startsWith("/") ? uri : "/" + uri;
  }

  private void addOpenTableAuthorization(HttpHeaders headers) {
    headers.setBearerAuth(openTableAccessToken());
  }

  private String openTableAccessToken() {
    CachedToken cached = openTableToken;
    Instant now = Instant.now();
    if (cached != null && cached.expiresAt().isAfter(now.plusSeconds(30))) {
      return cached.value();
    }
    JsonNode response =
        openTableOauthClient
            .get()
            .uri("/api/v2/oauth/token?grant_type=client_credentials")
            .headers(
                headers ->
                    headers.setBasicAuth(
                        properties.getOpentable().getClientId(),
                        properties.getOpentable().getClientSecret(),
                        StandardCharsets.UTF_8))
            .accept(MediaType.APPLICATION_JSON)
            .retrieve()
            .bodyToMono(JsonNode.class)
            .block(openTableTimeout());
    String accessToken = firstText(response, "access_token", "accessToken", "token");
    if (StringUtils.isBlank(accessToken)) {
      throw new IllegalStateException(
          "OpenTable OAuth token response did not include access_token");
    }
    long expiresIn = response == null ? 3600 : response.path("expires_in").asLong(3600);
    CachedToken next = new CachedToken(accessToken, now.plusSeconds(Math.max(60, expiresIn)));
    openTableToken = next;
    return next.value();
  }

  private AvailabilityResponse handoffAvailability(
      String provider,
      String restaurantId,
      String restaurantName,
      String location,
      String dateTime,
      Integer partySize,
      String note) {
    return new AvailabilityResponse(
        "handoff_required",
        provider,
        restaurantId,
        restaurantName,
        dateTime,
        partySize,
        List.of(),
        List.of(),
        List.of(providerLink(provider, restaurantName, location, dateTime, partySize)),
        List.of(note));
  }

  private BookingResponse handoffBooking(String provider, BookingRequest request, String note) {
    return new BookingResponse(
        "handoff_required",
        provider,
        request.restaurantId(),
        request.restaurantName(),
        null,
        request.dateTime(),
        request.partySize(),
        null,
        null,
        note,
        List.of(
            providerLink(
                provider,
                request.restaurantName(),
                request.location(),
                request.dateTime(),
                request.partySize())),
        List.of("Have the user complete login and booking with the provider web flow."));
  }

  private AvailabilityResponse availabilityValidationError(
      String provider, String restaurantId, String note) {
    return new AvailabilityResponse(
        "validation_error",
        provider,
        restaurantId,
        null,
        null,
        null,
        List.of(),
        List.of(),
        List.of(),
        List.of(note));
  }

  private BookingResponse bookingValidationError(
      String provider, String restaurantId, String note) {
    return new BookingResponse(
        "validation_error",
        provider,
        restaurantId,
        null,
        null,
        null,
        null,
        null,
        null,
        note,
        List.of(),
        List.of(note));
  }

  private List<String> missingOpenTableBookingFields(BookingRequest request) {
    List<String> missing = new ArrayList<>();
    if (StringUtils.isBlank(request.restaurantId())) {
      missing.add("restaurantId");
    }
    if (StringUtils.isBlank(request.dateTime())) {
      missing.add("dateTime");
    }
    if (request.partySize() == null) {
      missing.add("partySize");
    }
    if (StringUtils.isBlank(request.firstName())) {
      missing.add("firstName");
    }
    if (StringUtils.isBlank(request.lastName())) {
      missing.add("lastName");
    }
    if (StringUtils.isBlank(request.email())) {
      missing.add("email");
    }
    if (StringUtils.isBlank(request.phoneNumber())) {
      missing.add("phoneNumber");
    }
    return missing;
  }

  private List<AvailabilitySlot> parseAvailabilitySlots(JsonNode response) {
    JsonNode slotsNode =
        firstArray(
            response,
            "times_available",
            "timesAvailable",
            "slots",
            "availability",
            "times",
            "data",
            "results");
    if (slotsNode == null || !slotsNode.isArray()) {
      return List.of();
    }
    List<AvailabilitySlot> slots = new ArrayList<>();
    for (JsonNode item : slotsNode) {
      if (item == null || item.isNull()) {
        continue;
      }
      if (item.isTextual()) {
        slots.add(
            new AvailabilitySlot(
                item.asText(),
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null));
        continue;
      }
      String dateTime =
          firstText(item, "date_time", "dateTime", "datetime", "time", "start_time", "startTime");
      if (StringUtils.isBlank(dateTime)) {
        continue;
      }
      slots.add(
          new AvailabilitySlot(
              dateTime,
              firstText(item, "booking_url", "bookingUrl", "reservation_url", "reservationUrl"),
              firstText(item, "booking_restref_url", "bookingRestrefUrl"),
              firstText(item, "reservation_attribute", "reservationAttribute"),
              firstText(item, "environment"),
              firstInt(item, "dining_area_id", "diningAreaId", "dining_area"),
              stringList(firstArray(item, "attributes")),
              intList(firstArray(item, "experience_ids", "experienceIds")),
              firstBoolean(
                  item,
                  "credit_card_required",
                  "creditCardRequired",
                  "requires_credit_card",
                  "requiresCreditCard"),
              firstText(item, "deposit_type", "depositType"),
              firstText(item, "cancellation_policy", "cancellationPolicy")));
    }
    return slots;
  }

  private List<String> parseNoAvailabilityReasons(JsonNode response) {
    JsonNode reasonsNode =
        firstArray(response, "no_availability_reasons", "noAvailabilityReasons", "reasons");
    return reasonsNode == null ? List.of() : stringList(reasonsNode);
  }

  private JsonNode firstArray(JsonNode node, String... fields) {
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isArray()) {
      return node;
    }
    for (String field : fields) {
      JsonNode child = node.path(field);
      if (child.isArray()) {
        return child;
      }
      if (child.isObject()) {
        JsonNode nested = firstArray(child, fields);
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }

  private String firstText(JsonNode node, String... fields) {
    if (node == null || node.isNull()) {
      return null;
    }
    for (String field : fields) {
      JsonNode child = node.path(field);
      if (child.isMissingNode() || child.isNull()) {
        continue;
      }
      if (child.isTextual() || child.isNumber() || child.isBoolean()) {
        String value = child.asText();
        if (StringUtils.isNotBlank(value)) {
          return value;
        }
      }
      if (child.isObject()) {
        String value = firstText(child, "value", "name", "description", "text", "url", "id");
        if (StringUtils.isNotBlank(value)) {
          return value;
        }
      }
    }
    return null;
  }

  private Integer firstInt(JsonNode node, String... fields) {
    if (node == null || node.isNull()) {
      return null;
    }
    for (String field : fields) {
      JsonNode child = node.path(field);
      if (child.isInt() || child.isLong()) {
        return child.asInt();
      }
      if (child.isTextual()) {
        try {
          return Integer.parseInt(child.asText());
        } catch (NumberFormatException ignored) {
          // try the next field
        }
      }
      if (child.isObject()) {
        Integer nested = firstInt(child, "id", "value");
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }

  private Boolean firstBoolean(JsonNode node, String... fields) {
    if (node == null || node.isNull()) {
      return null;
    }
    for (String field : fields) {
      JsonNode child = node.path(field);
      if (child.isBoolean()) {
        return child.asBoolean();
      }
      if (child.isTextual()) {
        String text = child.asText();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
          return Boolean.parseBoolean(text);
        }
      }
    }
    return null;
  }

  private List<String> stringList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> values = new ArrayList<>();
    for (JsonNode item : node) {
      String value;
      if (item.isObject()) {
        value = firstText(item, "value", "name", "description", "text", "id");
      } else {
        value = item.asText(null);
      }
      if (StringUtils.isNotBlank(value)) {
        values.add(value);
      }
    }
    return values;
  }

  private List<Integer> intList(JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<Integer> values = new ArrayList<>();
    for (JsonNode item : node) {
      if (item.isInt() || item.isLong()) {
        values.add(item.asInt());
        continue;
      }
      if (item.isTextual()) {
        try {
          values.add(Integer.parseInt(item.asText()));
        } catch (NumberFormatException ignored) {
          // skip non-numeric items
        }
      }
    }
    return values;
  }

  private ProviderLink providerLink(
      String provider, String query, String location, String dateTime, Integer partySize) {
    if (PROVIDER_RESY.equals(provider)) {
      return resySearchLink(query, location, dateTime, partySize);
    }
    return openTableSearchLink(query, location, dateTime, partySize);
  }

  private ProviderLink openTableSearchLink(
      String query, String location, String dateTime, Integer partySize) {
    String term = joinSearchTerms(query, location);
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromUriString("https://www.opentable.com/s/");
    if (StringUtils.isNotBlank(term)) {
      builder.queryParam("term", term);
    }
    if (StringUtils.isNotBlank(dateTime)) {
      builder.queryParam("dateTime", dateTime);
    }
    if (partySize != null) {
      builder.queryParam("covers", partySize);
    }
    return new ProviderLink(
        PROVIDER_OPENTABLE, "OpenTable search/login/booking", builder.toUriString());
  }

  private ProviderLink resySearchLink(
      String query, String location, String dateTime, Integer partySize) {
    String term = joinSearchTerms(query, location);
    UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(resyBaseUrl() + "/search");
    if (StringUtils.isNotBlank(term)) {
      builder.queryParam("query", term);
    }
    if (StringUtils.isNotBlank(dateTime)) {
      builder.queryParam("date", dateTime);
    }
    if (partySize != null) {
      builder.queryParam("seats", partySize);
    }
    return new ProviderLink(PROVIDER_RESY, "Resy search/login/booking", builder.toUriString());
  }

  private String expandSearchTemplate(String template, SearchRequest request, int limit) {
    String result = template;
    result = result.replace("{query}", encodeQueryValue(request.query()));
    result = result.replace("{location}", encodeQueryValue(request.location()));
    result = result.replace("{dateTime}", encodeQueryValue(request.dateTime()));
    result =
        result.replace(
            "{partySize}", request.partySize() == null ? "" : request.partySize().toString());
    result = result.replace("{limit}", Integer.toString(limit));
    return result;
  }

  private String encodeQueryValue(String value) {
    if (value == null) {
      return "";
    }
    return UriUtils.encodeQueryParam(value, StandardCharsets.UTF_8);
  }

  private String joinSearchTerms(String query, String location) {
    List<String> parts = new ArrayList<>();
    if (StringUtils.isNotBlank(query)) {
      parts.add(query.trim());
    }
    if (StringUtils.isNotBlank(location)) {
      parts.add(location.trim());
    }
    return String.join(" ", parts);
  }

  private boolean includesProvider(String requestedProvider, String provider) {
    return PROVIDER_ALL.equals(requestedProvider) || provider.equals(requestedProvider);
  }

  private String normalizeProvider(String provider, boolean allowAll) {
    String normalized =
        StringUtils.defaultIfBlank(provider, allowAll ? PROVIDER_ALL : "")
            .trim()
            .toLowerCase(Locale.ROOT);
    if (allowAll && normalized.isBlank()) {
      return PROVIDER_ALL;
    }
    if (allowAll && "both".equals(normalized)) {
      return PROVIDER_ALL;
    }
    return normalized;
  }

  private int safeLimit(Integer limit) {
    if (limit == null) {
      return DEFAULT_SEARCH_LIMIT;
    }
    return Math.max(1, Math.min(MAX_SEARCH_LIMIT, limit));
  }

  private int defaulted(Integer value, int defaultValue) {
    return value == null ? defaultValue : value;
  }

  private String compactError(RuntimeException e) {
    Throwable current = e;
    while (current instanceof WebClientResponseException responseException) {
      String body = responseException.getResponseBodyAsString();
      if (StringUtils.isNotBlank(body)) {
        return responseException.getStatusCode()
            + " "
            + StringUtils.abbreviate(body.replaceAll("\\s+", " "), 300);
      }
      break;
    }
    String message = e.getMessage();
    return StringUtils.defaultIfBlank(message, e.getClass().getSimpleName());
  }

  private void addIfPresent(Map<String, Object> body, String key, Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof String stringValue) {
      if (StringUtils.isBlank(stringValue)) {
        return;
      }
      body.put(key, stringValue.trim());
      return;
    }
    body.put(key, value);
  }

  private String truncateSpecialRequest(String value) {
    String trimmed = StringUtils.trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    return StringUtils.abbreviate(trimmed, 75);
  }

  private boolean openTableApiConfigured() {
    return properties.getOpentable().isEnabled()
        && StringUtils.isNotBlank(properties.getOpentable().getBaseUrl())
        && StringUtils.isNotBlank(properties.getOpentable().getClientId())
        && StringUtils.isNotBlank(properties.getOpentable().getClientSecret());
  }

  private boolean openTableSearchConfigured() {
    return openTableApiConfigured()
        && StringUtils.isNotBlank(properties.getOpentable().getDirectorySearchUrlTemplate());
  }

  private Duration openTableTimeout() {
    return Duration.ofSeconds(Math.max(1, properties.getOpentable().getTimeoutSeconds()));
  }

  private String openTableBaseUrl() {
    return StringUtils.defaultIfBlank(
        properties.getOpentable().getBaseUrl(), "https://api.opentable.com");
  }

  private String openTableOauthUrl() {
    return StringUtils.defaultIfBlank(
        properties.getOpentable().getOauthUrl(), "https://oauth.opentable.com");
  }

  private String resyBaseUrl() {
    return StringUtils.removeEnd(
        StringUtils.defaultIfBlank(properties.getResy().getBaseUrl(), "https://resy.com"), "/");
  }

  private String resyLoginUrl() {
    return StringUtils.defaultIfBlank(properties.getResy().getLoginUrl(), resyBaseUrl() + "/login");
  }

  private record CachedToken(String value, Instant expiresAt) {}

  private record SlotLock(String token, String expiresAt) {}
}
