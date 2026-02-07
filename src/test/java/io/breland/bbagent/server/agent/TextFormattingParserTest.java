package io.breland.bbagent.server.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.breland.bbagent.generated.bluebubblesclient.model.TextFormattingRange;
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

  private static void assertRange(
      TextFormattingRange range, int start, int length, List<String> styles) {
    assertEquals(start, range.getStart());
    assertEquals(length, range.getLength());
    assertEquals(styles, range.getStyles());
  }
}
