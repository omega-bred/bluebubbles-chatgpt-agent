package io.breland.bbagent.server.agent;

import io.breland.bbagent.generated.bluebubblesclient.model.TextFormattingRange;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TextFormattingParser {

  record Result(String text, List<TextFormattingRange> formatting) {}

  private static final int STYLE_BOLD = 1;
  private static final int STYLE_ITALIC = 1 << 1;
  private static final int STYLE_UNDERLINE = 1 << 2;
  private static final int STYLE_STRIKETHROUGH = 1 << 3;

  private static final List<Delimiter> DELIMITERS =
      List.of(
          new Delimiter("**", STYLE_BOLD),
          new Delimiter("__", STYLE_UNDERLINE),
          new Delimiter("~~", STYLE_STRIKETHROUGH),
          new Delimiter("*", STYLE_ITALIC),
          new Delimiter("_", STYLE_ITALIC));

  private TextFormattingParser() {}

  static Result parse(String input) {
    if (input == null || input.isEmpty()) {
      return new Result(input, List.of());
    }

    List<Token> tokens = collectTokens(input);
    Map<Integer, Token> matchedTokens = matchTokens(tokens);

    StringBuilder output = new StringBuilder(input.length());
    List<TextFormattingRange> ranges = new ArrayList<>();

    int activeMask = 0;
    int lastMask = 0;
    int segmentStart = 0;

    int i = 0;
    while (i < input.length()) {
      Token token = matchedTokens.get(i);
      if (token != null) {
        if (token.isOpen) {
          activeMask |= token.delimiter.styleBit;
        } else {
          activeMask &= ~token.delimiter.styleBit;
        }
        i += token.delimiter.length();
        continue;
      }

      if (activeMask != lastMask) {
        if (lastMask != 0) {
          addRange(ranges, segmentStart, output.length() - segmentStart, lastMask);
        }
        if (activeMask != 0) {
          segmentStart = output.length();
        }
        lastMask = activeMask;
      }

      output.append(input.charAt(i));
      i++;
    }

    if (lastMask != 0) {
      addRange(ranges, segmentStart, output.length() - segmentStart, lastMask);
    }

    return new Result(output.toString(), ranges);
  }

  private static List<Token> collectTokens(String input) {
    List<Token> tokens = new ArrayList<>();
    int i = 0;
    while (i < input.length()) {
      Delimiter delimiter = matchDelimiter(input, i);
      if (delimiter == null) {
        i++;
        continue;
      }

      int nextIndex = i + delimiter.length();
      char prev = i > 0 ? input.charAt(i - 1) : 0;
      char next = nextIndex < input.length() ? input.charAt(nextIndex) : 0;
      boolean hasPrev = i > 0;
      boolean hasNext = nextIndex < input.length();

      boolean canOpen = canOpen(delimiter, prev, next, hasPrev, hasNext);
      boolean canClose = canClose(delimiter, prev, next, hasPrev, hasNext);

      if (canOpen || canClose) {
        tokens.add(new Token(i, delimiter, canOpen, canClose));
      }

      i += delimiter.length();
    }
    return tokens;
  }

  private static Map<Integer, Token> matchTokens(List<Token> tokens) {
    Map<Integer, Token> matched = new HashMap<>();
    Deque<Token> stack = new ArrayDeque<>();

    for (Token token : tokens) {
      if (token.canClose && !stack.isEmpty() && stack.peek().delimiter.matches(token.delimiter)) {
        Token open = stack.pop();
        open.matched = true;
        open.isOpen = true;
        token.matched = true;
        token.isOpen = false;
        matched.put(open.index, open);
        matched.put(token.index, token);
      } else if (token.canOpen) {
        stack.push(token);
      }
    }

    return matched;
  }

  private static Delimiter matchDelimiter(String input, int index) {
    for (Delimiter delimiter : DELIMITERS) {
      if (input.startsWith(delimiter.marker, index)) {
        return delimiter;
      }
    }
    return null;
  }

  private static boolean canOpen(
      Delimiter delimiter, char prev, char next, boolean hasPrev, boolean hasNext) {
    if (!hasNext || Character.isWhitespace(next)) {
      return false;
    }
    if (delimiter.isUnderscore() && hasPrev && isWordChar(prev)) {
      return false;
    }
    return true;
  }

  private static boolean canClose(
      Delimiter delimiter, char prev, char next, boolean hasPrev, boolean hasNext) {
    if (!hasPrev || Character.isWhitespace(prev)) {
      return false;
    }
    if (delimiter.isUnderscore() && hasNext && isWordChar(next)) {
      return false;
    }
    return true;
  }

  private static boolean isWordChar(char value) {
    return Character.isLetterOrDigit(value) || value == '_';
  }

  private static void addRange(
      List<TextFormattingRange> ranges, int start, int length, int mask) {
    if (length <= 0) {
      return;
    }
    ranges.add(
        new TextFormattingRange()
            .start(start)
            .length(length)
            .styles(stylesForMask(mask)));
  }

  private static List<String> stylesForMask(int mask) {
    List<String> styles = new ArrayList<>(4);
    if ((mask & STYLE_BOLD) != 0) {
      styles.add("bold");
    }
    if ((mask & STYLE_ITALIC) != 0) {
      styles.add("italic");
    }
    if ((mask & STYLE_UNDERLINE) != 0) {
      styles.add("underline");
    }
    if ((mask & STYLE_STRIKETHROUGH) != 0) {
      styles.add("strikethrough");
    }
    return styles;
  }

  private record Delimiter(String marker, int styleBit) {
    int length() {
      return marker.length();
    }

    boolean matches(Delimiter other) {
      return marker.equals(other.marker);
    }

    boolean isUnderscore() {
      return marker.startsWith("_");
    }
  }

  private static final class Token {
    private final int index;
    private final Delimiter delimiter;
    private final boolean canOpen;
    private final boolean canClose;
    private boolean matched;
    private boolean isOpen;

    private Token(int index, Delimiter delimiter, boolean canOpen, boolean canClose) {
      this.index = index;
      this.delimiter = delimiter;
      this.canOpen = canOpen;
      this.canClose = canClose;
    }
  }
}
