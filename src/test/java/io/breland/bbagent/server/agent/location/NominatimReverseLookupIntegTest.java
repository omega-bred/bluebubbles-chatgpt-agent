package io.breland.bbagent.server.agent.location;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest()
public class NominatimReverseLookupIntegTest {

  @Autowired NominatimReverseLocationLookup reverseLocationLookup;

  @Test
  @Disabled
  public void testReverseLookup() {
    Optional<ReverseLocationLookupResult> result =
        reverseLocationLookup.reverseLookup(37.33182, -122.03118);

    assertTrue(result.isPresent());
    assertEquals(
        "Mariani Avenue, Cupertino, Santa Clara County, California, 95014, United States",
        result.get().approximateAddress());
  }
}
