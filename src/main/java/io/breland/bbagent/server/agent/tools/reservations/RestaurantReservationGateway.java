package io.breland.bbagent.server.agent.tools.reservations;

public interface RestaurantReservationGateway {
  RestaurantReservationModels.CapabilityResponse capabilities();

  RestaurantReservationModels.SearchResponse searchRestaurants(
      RestaurantReservationModels.SearchRequest request);

  RestaurantReservationModels.AvailabilityResponse checkAvailability(
      RestaurantReservationModels.AvailabilityRequest request);

  RestaurantReservationModels.BookingResponse makeReservation(
      RestaurantReservationModels.BookingRequest request);
}
