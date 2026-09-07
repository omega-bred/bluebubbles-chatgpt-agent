package io.breland.bbagent.server.agent.tools.bb;

import static io.breland.bbagent.server.agent.tools.JsonSchemaUtilities.functionParameters;

import io.breland.bbagent.server.agent.IncomingMessage;
import io.breland.bbagent.server.agent.tools.AgentTool;
import io.breland.bbagent.server.agent.tools.ToolProvider;
import io.breland.bbagent.server.agent.tools.wallart.WallartImageInputs;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class GetGroupIconAgentTool implements ToolProvider {
  public static final String TOOL_NAME = "get_group_icon";
  private final BBHttpClientWrapper blueBubbles;

  @Override
  public AgentTool getTool() {
    return new AgentTool(
        TOOL_NAME,
        "Retrieve this group chat's current icon/profile photo and send a copy as an image in this same chat. Does not change the group icon. No arguments; never select another chat. Reports unavailable if the photo is not stored on the server. For wall-art compositions use source=group_icon directly, without sending the photo first.",
        functionParameters(
            Map.of("type", "object", "properties", Map.of(), "additionalProperties", false)),
        false,
        (context, args) -> {
          IncomingMessage message = context.message();
          String chatGuid = IncomingMessage.chatGuidOrNull(message);
          if (chatGuid == null || !message.isGroup() || !message.isBlueBubblesTransport())
            return "unavailable: get_group_icon requires a current BlueChat group conversation.";
          if (!context.canSendResponses()) return "skipped: outdated workflow";
          WallartImageInputs.ImageData image;
          try {
            var bytes = blueBubbles.getConversationIcon(chatGuid);
            if (bytes.isEmpty())
              return "unavailable: this group's photo is not available on the BlueBubbles server. Ask for a photo attachment if needed.";
            image = WallartImageInputs.normalizePhoto(bytes.get());
          } catch (RuntimeException e) {
            return "failed: the group photo could not be retrieved or read. Do not claim it was sent.";
          }
          if (!context.canSendResponses()) return "skipped: outdated workflow";
          if (!context.consumeMessageResponseQuota()) return "rate limited";
          String filename =
              "group-icon" + ("image/png".equals(image.mimeType()) ? ".png" : ".jpeg");
          try {
            boolean sent =
                blueBubbles.sendMultipartMessage(
                    chatGuid,
                    null,
                    List.of(new BBHttpClientWrapper.AttachmentData(filename, image.bytes())));
            if (!sent)
              return "failed: the group photo was retrieved but delivery could not be confirmed. Do not automatically retry or claim it was sent.";
          } catch (RuntimeException e) {
            return "failed: group photo delivery could not be confirmed. Do not automatically retry or claim it was sent.";
          }
          context.recordAssistantTurn("[current group icon photo]");
          return "Current group icon photo sent to this conversation. The group icon was not changed.";
        });
  }
}
