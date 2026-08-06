package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.breland.bbagent.generated.bluebubblesclient.model.TextFormattingRange;
import io.breland.bbagent.server.agent.transport.bb.TextFormattingParser;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextFormattingParserTest {

  @Test
  void parsesMarkdownFormattingIntoRanges() {
    TextFormattingParser.Result result =
        TextFormattingParser.parse("**Bold** *Italic* __Underline__ ~~Strike~~");

    assertEquals("Bold Italic Underline Strike", result.text());

    List<TextFormattingRange> ranges = result.formatting();
    assertEquals(4, ranges.size());

    assertRange(ranges.get(0), 0, 4, List.of("bold"));
    assertRange(ranges.get(1), 5, 6, List.of("italic"));
    assertRange(ranges.get(2), 12, 9, List.of("underline"));
    assertRange(ranges.get(3), 22, 6, List.of("strikethrough"));
  }

  @Test
  void replacesMarkdownLinkWithFullUrl() {
    TextFormattingParser.Result result =
        TextFormattingParser.parse("Read [the documentation](https://example.com/docs) first.");

    assertEquals("Read https://example.com/docs first.", result.text());
    assertEquals(List.of(), result.formatting());
  }

  @Test
  void replacesEveryMarkdownLinkWithItsFullUrl() {
    TextFormattingParser.Result result =
        TextFormattingParser.parse(
            "Try [search](https://example.com/search) or [status](https://status.example.com).");

    assertEquals("Try https://example.com/search or https://status.example.com.", result.text());
    assertEquals(List.of(), result.formatting());
  }

  @Test
  void preservesFullUrlAndFormattingAroundMarkdownLink() {
    String url = "https://example.com/a_(b)/~~literal~~";
    TextFormattingParser.Result result = TextFormattingParser.parse("**[reference](" + url + ")**");

    assertEquals(url, result.text());
    assertEquals(1, result.formatting().size());
    assertRange(result.formatting().getFirst(), 0, url.length(), List.of("bold"));
  }

  private static void assertRange(
      TextFormattingRange range, int start, int length, List<String> styles) {
    assertEquals(start, range.getStart());
    assertEquals(length, range.getLength());
    assertEquals(styles, range.getStyles());
  }
}
