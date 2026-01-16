package io.breland.bbagent.server.agent.tools.giphy;

import static io.breland.bbagent.server.agent.BBMessageAgent.getOptionalText;
import static io.breland.bbagent.server.agent.BBMessageAgent.getRequired;
import static io.breland.bbagent.server.agent.BBMessageAgent.jsonSchema;

import com.fasterxml.jackson.databind.JsonNode;
import com.openai.client.OpenAIClient;
import com.openai.models.ChatModel;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolContext;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SendGiphyAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "send_giphy";
  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final GiphyClient giphyClient;
  private final Supplier<OpenAIClient> openAiSupplier;

  public SendGiphyAgentTool(
      BBHttpClientWrapper bbHttpClientWrapper,
      GiphyClient giphyClient,
      Supplier<OpenAIClient> openAiSupplier) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.giphyClient = giphyClient;
    this.openAiSupplier = openAiSupplier;
  }

  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Search Giphy for a GIF and send it as a reply in the current conversation. If your response would be better describe as the perfect gif - use this tool to find and send it. ",
        jsonSchema(
            Map.of(
                "type",
                "object",
                "properties",
                Map.of(
                    "query",
                    Map.of("type", "string"),
                    "caption",
                    Map.of("type", "string"),
                    "rating",
                    Map.of("type", "string"),
                    "lang",
                    Map.of("type", "string")),
                "required",
                List.of("query"))),
        false,
        this::sendGif);
  }

  private String sendGif(ToolContext context, JsonNode args) {
    IncomingMessage message = context.message();
    if (message == null || message.chatGuid() == null || message.chatGuid().isBlank()) {
      return "no chat";
    }
    String query = getRequired(args, "query");
    String caption = getOptionalText(args, "caption");
    String rating = getOptionalText(args, "rating");
    String lang = getOptionalText(args, "lang");

    List<GiphyClient.GiphyGif> candidates = giphyClient.searchGifs(query, 8, rating, lang);
    if (candidates.isEmpty()) {
      return "no results";
    }
    int bestIndex = pickBestGifIndex(query, caption, candidates);
    if (bestIndex < 0 || bestIndex >= candidates.size()) {
      bestIndex = 0;
    }
    GiphyClient.GiphyGif selected = candidates.get(bestIndex);
    Optional<byte[]> bytes = giphyClient.downloadGifBytes(selected.url());
    if (bytes.isEmpty()) {
      return "download failed";
    }
    String filename =
        selected.id() == null || selected.id().isBlank() ? "giphy.gif" : selected.id() + ".gif";
    boolean sent =
        bbHttpClientWrapper.sendMultipartMessage(
            message.chatGuid(),
            caption,
            List.of(new BBHttpClientWrapper.AttachmentData(filename, bytes.get())));
    if (sent) {
      if (caption != null && !caption.isBlank()) {
        context.recordAssistantTurn(caption);
      } else {
        context.recordAssistantTurn("[gif]");
      }
      return "sent";
    }
    return "failed";
  }

  private int pickBestGifIndex(
      String query, String caption, List<GiphyClient.GiphyGif> candidates) {
    if (candidates.size() <= 1 || openAiSupplier == null) {
      return 0;
    }
    try {
      List<GiphyClient.GiphyGif> visualCandidates =
          candidates.stream()
              .filter(candidate -> candidate.stillUrl() != null && !candidate.stillUrl().isBlank())
              .limit(5)
              .collect(Collectors.toList());
      List<ResponseInputItem> input =
          buildPickerInput(query, caption, candidates, visualCandidates);

      ResponseCreateParams params =
          ResponseCreateParams.builder()
              .model(ChatModel.GPT_5_CHAT_LATEST)
              .inputOfResponse(input)
              .temperature(0.0)
              .maxOutputTokens(30)
              .build();
      Response response = openAiSupplier.get().responses().create(params);
      String text = extractResponseText(response);
      Integer index = parseIndex(text);
      if (index != null) {
        return index;
      }
    } catch (Exception ignored) {
      // fall back to first result
    }
    return 0;
  }

  private List<ResponseInputItem> buildPickerInput(
      String query,
      String caption,
      List<GiphyClient.GiphyGif> candidates,
      List<GiphyClient.GiphyGif> visualCandidates) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("Pick the single best GIF for this chat reply.\n");
    prompt.append("User intent: ").append(query).append('\n');
    if (caption != null && !caption.isBlank()) {
      prompt.append("Optional caption: ").append(caption).append('\n');
    }
    prompt.append("Respond with only the index number.");

    if (visualCandidates.isEmpty()) {
      StringBuilder list = new StringBuilder();
      list.append("Candidates:\n");
      for (int i = 0; i < candidates.size(); i++) {
        GiphyClient.GiphyGif gif = candidates.get(i);
        list.append(i)
            .append(": title=\"")
            .append(gif.title() == null ? "" : gif.title())
            .append("\" url=\"")
            .append(gif.url())
            .append("\"\n");
      }
      return List.of(
          ResponseInputItem.ofEasyInputMessage(
              EasyInputMessage.builder()
                  .role(EasyInputMessage.Role.DEVELOPER)
                  .content("You must return only a single integer index.")
                  .build()),
          ResponseInputItem.ofEasyInputMessage(
              EasyInputMessage.builder()
                  .role(EasyInputMessage.Role.USER)
                  .content(list.append(prompt).toString())
                  .build()));
    }

    List<ResponseInputContent> content = new java.util.ArrayList<>();
    content.add(
        ResponseInputContent.ofInputText(
            ResponseInputText.builder()
                .text(
                    "We have candidate GIF thumbnails to choose from. Each thumbnail is labeled by index.\n"
                        + prompt)
                .build()));
    for (GiphyClient.GiphyGif gif : visualCandidates) {
      int index = candidates.indexOf(gif);
      content.add(
          ResponseInputContent.ofInputText(
              ResponseInputText.builder().text("Candidate " + index).build()));
      content.add(
          ResponseInputContent.ofInputImage(
              ResponseInputImage.builder()
                  .detail(ResponseInputImage.Detail.AUTO)
                  .imageUrl(gif.stillUrl())
                  .build()));
    }
    return List.of(
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.DEVELOPER)
                .content("You must return only a single integer index.")
                .build()),
        ResponseInputItem.ofEasyInputMessage(
            EasyInputMessage.builder()
                .role(EasyInputMessage.Role.USER)
                .contentOfResponseInputMessageContentList(content)
                .build()));
  }

  private static Integer parseIndex(String text) {
    if (text == null) {
      return null;
    }
    Matcher matcher = Pattern.compile("-?\\d+").matcher(text);
    if (matcher.find()) {
      try {
        return Integer.parseInt(matcher.group());
      } catch (NumberFormatException ignored) {
        return null;
      }
    }
    return null;
  }

  private static String extractResponseText(Response response) {
    if (response == null || response.output() == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    for (ResponseOutputItem item : response.output()) {
      if (item.message().isEmpty()) {
        continue;
      }
      var message = item.message().get();
      for (var content : message.content()) {
        if (content.isOutputText()) {
          if (builder.length() > 0) {
            builder.append(' ');
          }
          builder.append(content.asOutputText().text());
        }
      }
    }
    return builder.toString().trim();
  }
}
