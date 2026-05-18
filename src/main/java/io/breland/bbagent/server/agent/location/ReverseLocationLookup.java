package io.breland.bbagent.server.agent.location;

import java.util.Optional;

public interface ReverseLocationLookup {
  Optional<ReverseLocationLookupResult> reverseLookup(double latitude, double longitude);

  static ReverseLocationLookup noop() {
    return (latitude, longitude) -> Optional.empty();
  }
}
