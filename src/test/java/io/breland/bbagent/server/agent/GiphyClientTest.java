package io.breland.bbagent.server.agent;

import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest()
@Disabled
public class GiphyClientTest {

  @Autowired public GiphyClient giphyClient;

  @Test
  public void testGiphyClient() {
    Optional<GiphyClient.GiphyGif> hotdogs = giphyClient.searchTopGif("hotdogs");
    assert hotdogs.isPresent();
    assert hotdogs.get().url() != null;
    System.out.println(hotdogs.get().url());
  }
}
