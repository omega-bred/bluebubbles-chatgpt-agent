package io.breland.bbagent.server.agent.cadence;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.models.responses.Response;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class GeneratedImageExtractorTest {
  @Test
  void extractsEveryCompletedImageInResponseOrder() throws Exception {
    Response response =
        new ObjectMapper()
            .readValue(
                """
        {"output":[
          {"type":"image_generation_call","id":"ig-first","status":"completed","result":"AQID"},
          {"type":"image_generation_call","id":"ig-failed","status":"failed"},
          {"type":"image_generation_call","id":"ig-empty","status":"completed","result":""},
          {"type":"image_generation_call","id":"ig-bad","status":"completed","result":"!invalid"},
          {"type":"image_generation_call","id":"ig-second","status":"completed","result":"data:image/png;base64,BAUG"}
        ]}
        """,
                Response.class);
    var images = new GeneratedImageExtractor().extract(response);
    assertEquals(2, images.size());
    assertEquals("generated-ig-first.png", images.get(0).filename());
    assertArrayEquals(Base64.getDecoder().decode("AQID"), images.get(0).bytes());
    assertEquals("generated-ig-second.png", images.get(1).filename());
    assertArrayEquals(Base64.getDecoder().decode("BAUG"), images.get(1).bytes());
  }
}
