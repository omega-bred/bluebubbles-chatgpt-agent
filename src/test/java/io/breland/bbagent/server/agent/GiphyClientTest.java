package io.breland.bbagent.server.agent;

import io.breland.bbagent.server.agent.tools.giphy.GiphyClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest()
@DisabledOnOs(OS.LINUX)
public class GiphyClientTest {

  @Autowired public GiphyClient giphyClient;

  @Test
  public void testGiphyClient() {
    var hotdogs = giphyClient.searchGifs("hotdogs", 5, null, "en");
    assert !hotdogs.isEmpty();
    assert hotdogs.getFirst().url() != null;
    System.out.println(hotdogs.getFirst().url());
  }
}
