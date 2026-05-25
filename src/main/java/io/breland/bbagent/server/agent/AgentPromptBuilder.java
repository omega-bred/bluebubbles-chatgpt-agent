package io.breland.bbagent.server.agent;

import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import io.breland.bbagent.generated.bluebubblesclient.model.FindMyFriendLocation;
import io.breland.bbagent.server.agent.location.ReverseLocationLookup;
import io.breland.bbagent.server.agent.persistence.account.AgentAccountIdentityEntity;
import io.breland.bbagent.server.agent.profile.AgentProfileService;
import io.breland.bbagent.server.agent.profile.AssistantResponsiveness;
import io.breland.bbagent.server.agent.tools.assistant.AssistantNameAgentTool;
import io.breland.bbagent.server.agent.tools.assistant.AssistantResponsivenessAgentTool;
import io.breland.bbagent.server.agent.tools.bb.CurrentConversationInfoAgentTool;
import io.breland.bbagent.server.agent.tools.bb.GetThreadContextAgentTool;
import io.breland.bbagent.server.agent.tools.bb.ReadPollAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SearchConvoHistoryAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendPollAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendReactionAgentTool;
import io.breland.bbagent.server.agent.tools.bb.SendTextAgentTool;
import io.breland.bbagent.server.agent.tools.coder.CoderAuthAgentTool;
import io.breland.bbagent.server.agent.tools.coder.CoderMcpClient;
import io.breland.bbagent.server.agent.tools.coder.StartCoderAsyncTaskAgentTool;
import io.breland.bbagent.server.agent.tools.feedback.FeedbackAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.CreateEventAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.DeleteEventAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GetCurrentTimeAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GetEventAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.GetFreebusyAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.ListCalendarsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.ListColorsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.ListEventsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.ManageAccountsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.RespondToEventAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.SearchEventsAgentTool;
import io.breland.bbagent.server.agent.tools.gcal.UpdateEventAgentTool;
import io.breland.bbagent.server.agent.tools.giphy.SendGiphyAgentTool;
import io.breland.bbagent.server.agent.tools.limits.GetUsageLimitsAgentTool;
import io.breland.bbagent.server.agent.tools.memory.MemoryDeleteAgentTool;
import io.breland.bbagent.server.agent.tools.memory.MemoryGetAgentTool;
import io.breland.bbagent.server.agent.tools.memory.MemorySaveAgentTool;
import io.breland.bbagent.server.agent.tools.memory.MemoryUpdateAgentTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventDeleteTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventListTool;
import io.breland.bbagent.server.agent.tools.scheduled.ScheduledEventTool;
import io.breland.bbagent.server.agent.tools.website.GetWebsiteAccountLinkStatusAgentTool;
import io.breland.bbagent.server.agent.tools.website.LinkWebsiteAccountAgentTool;
import io.breland.bbagent.server.agent.transport.bb.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.workflowcallback.WorkflowCallbackService;
import io.breland.bbagent.server.feedback.FeedbackService;
import io.breland.bbagent.server.website.WebsiteAccountService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public final class AgentPromptBuilder {
  private static final String IMESSAGE_FORMATTING_INSTRUCTION =
      "BlueChat supports basic text formatting, specifically bold, italic, underline, and"
          + " strikethrough. Bold is delimited with **, underline with __, strikethrough with ~~, and"
          + " italic with *. Constrain output to those formatting markers, plain text, and emojis."
          + " Do not use unsupported markdown such as backticks, headings, tables, or lists. ";

  private final BBHttpClientWrapper bbHttpClientWrapper;
  private final ReverseLocationLookup reverseLocationLookup;
  private final AgentProfileService profileService;
  private final AgentAttachmentInputBuilder attachmentInputBuilder;
  private final @Nullable WebsiteAccountService websiteAccountService;
  private final @Nullable FeedbackService feedbackService;

  public AgentPromptBuilder(
      BBHttpClientWrapper bbHttpClientWrapper,
      ReverseLocationLookup reverseLocationLookup,
      AgentProfileService profileService,
      AgentAttachmentInputBuilder attachmentInputBuilder,
      @Nullable WebsiteAccountService websiteAccountService,
      @Nullable FeedbackService feedbackService) {
    this.bbHttpClientWrapper = bbHttpClientWrapper;
    this.reverseLocationLookup =
        reverseLocationLookup == null ? ReverseLocationLookup.noop() : reverseLocationLookup;
    this.profileService = profileService;
    this.attachmentInputBuilder = attachmentInputBuilder;
    this.websiteAccountService = websiteAccountService;
    this.feedbackService = feedbackService;
  }

  public List<ResponseInputItem> buildConversationInput(
      List<ConversationTurn> history,
      List<ConversationState.PendingIncomingTurn> pendingIncomingTurns,
      IncomingMessage message) {
    List<ResponseInputItem> items = new ArrayList<>();
    boolean isGroupMessage = message.isGroup();
    items.add(ResponseInputItem.ofEasyInputMessage(systemMessage(isGroupMessage, message)));
    items.add(ResponseInputItem.ofEasyInputMessage(developerMessage(message)));
    if (history != null) {
      for (ConversationTurn turn : history) {
        items.add(ResponseInputItem.ofEasyInputMessage(turn.toEasyInputMessage()));
      }
    }
    if (pendingIncomingTurns != null) {
      for (ConversationState.PendingIncomingTurn pending : pendingIncomingTurns) {
        if (pending == null || pending.turn() == null || pending.matches(message)) {
          continue;
        }
        items.add(ResponseInputItem.ofEasyInputMessage(pending.turn().toEasyInputMessage()));
      }
    }
    items.add(ResponseInputItem.ofEasyInputMessage(userMessage(message)));
    findMyLocationContextMessage(message)
        .ifPresent(
            locationMessage -> items.add(ResponseInputItem.ofEasyInputMessage(locationMessage)));
    return items;
  }

  private Optional<EasyInputMessage> findMyLocationContextMessage(IncomingMessage message) {
    if (message == null || message.isGroup() || !message.isBlueBubblesTransport()) {
      return Optional.empty();
    }
    if (message.sender() == null || message.sender().isBlank()) {
      return Optional.empty();
    }
    try {
      FindMyFriendLocation location =
          bbHttpClientWrapper.getFindMyLocation(findMyLocationIdentifiers(message));
      String locationContext = formatFindMyLocationContext(location);
      if (locationContext == null || locationContext.isBlank()) {
        locationContext = missingFindMyLocationContext();
      }
      return Optional.of(
          EasyInputMessage.builder()
              .role(EasyInputMessage.Role.DEVELOPER)
              .content(locationContext)
              .build());
    } catch (Exception e) {
      log.warn(
          "Failed to fetch Find My location context for chat={} sender={}",
          message.chatGuid(),
          message.sender(),
          e);
      return Optional.of(
          EasyInputMessage.builder()
              .role(EasyInputMessage.Role.DEVELOPER)
              .content(missingFindMyLocationContext())
              .build());
    }
  }

  private List<String> findMyLocationIdentifiers(IncomingMessage message) {
    if (message == null || message.sender() == null || message.sender().isBlank()) {
      return List.of();
    }
    LinkedHashSet<String> identifiers = new LinkedHashSet<>();
    identifiers.add(message.sender());
    profileService.resolveAccountIdentities(message).stream()
        .map(AgentAccountIdentityEntity::getIdentifier)
        .filter(StringUtils::isNotBlank)
        .forEach(identifiers::add);
    linkedWebsiteAccountEmail(message)
        .filter(email -> !email.isBlank())
        .filter(
            email -> identifiers.stream().noneMatch(existing -> existing.equalsIgnoreCase(email)))
        .ifPresent(identifiers::add);
    return List.copyOf(identifiers);
  }

  private Optional<String> linkedWebsiteAccountEmail(IncomingMessage message) {
    if (websiteAccountService == null) {
      return Optional.empty();
    }
    try {
      return websiteAccountService.findLinkedAccountEmail(message);
    } catch (Exception e) {
      log.debug("Failed to resolve linked website account email for Find My lookup", e);
      return Optional.empty();
    }
  }

  private String missingFindMyLocationContext() {
    return "No current location is available for the current BlueChat sender. "
        + "If the user asks where they are, asks for real-time location-based information or updates, "
        + "or asks something that would benefit from knowing their current location, do not guess. "
        + "Tell them they can share their location if they want real-time location-based information or updates.";
  }

  private String formatFindMyLocationContext(FindMyFriendLocation location) {
    if (location == null) {
      return null;
    }
    List<Double> coordinates = location.getCoordinates();
    if (coordinates == null || coordinates.size() < 2) {
      return null;
    }
    Double latitude = coordinates.get(0);
    Double longitude = coordinates.get(1);
    if (latitude == null || longitude == null) {
      return null;
    }

    StringBuilder text =
        new StringBuilder(
            "Current location context for the current BlueChat sender. "
                + "Use this as background for location-aware answers, but do not mention it unless relevant. ");
    text.append("latitude=").append(latitude).append(" longitude=").append(longitude);
    appendReverseLocationLookupField(text, latitude, longitude);
    appendFindMyLocationField(text, "short_address", location.getShortAddress());
    appendFindMyLocationField(text, "long_address", location.getLongAddress());
    appendFindMyLocationField(text, "title", location.getTitle());
    if (location.getStatus() != null) {
      text.append(" status=").append(location.getStatus().getValue());
    }
    if (location.getLastUpdated() != null) {
      text.append(" last_updated=").append(Instant.ofEpochMilli(location.getLastUpdated()));
    }
    return text.toString();
  }

  private void appendReverseLocationLookupField(
      StringBuilder text, double latitude, double longitude) {
    try {
      reverseLocationLookup
          .reverseLookup(latitude, longitude)
          .map(location -> location.approximateAddress())
          .filter(address -> address != null && !address.isBlank())
          .ifPresent(
              address ->
                  appendFindMyLocationField(text, "reverse_geocoded_approximate_address", address));
    } catch (Exception e) {
      log.warn("Failed to append reverse geocoded location context", e);
    }
  }

  private void appendFindMyLocationField(StringBuilder text, String name, String value) {
    if (value != null && !value.isBlank()) {
      text.append(" ").append(name).append("=").append(value.replaceAll("\\s+", " ").trim());
    }
  }

  private EasyInputMessage systemMessage(boolean groupMessage, IncomingMessage message) {
    AssistantResponsiveness responsiveness =
        profileService.getAssistantResponsiveness(message != null ? message.chatGuid() : null);
    String responsivenessInstruction =
        switch (responsiveness) {
          case LESS_RESPONSIVE ->
              "Responsiveness: ALWAYS REPLY "
                  + BBMessageAgent.NO_RESPONSE_TEXT
                  + " unless explicitly addressed, and do not issue any other response unless DIRECTLY ADDRESSED. No reacting unless directly asked. Don't engage in casual conversation, only reply to direct asks. Do not assume a message was meant for you unless you're directly addressed by name.";
          case MORE_RESPONSIVE ->
              "Responsiveness: more responsive. Act like an active participant, reply when helpful, and use reactions more freely. ";
          case SILENT ->
              "Responsiveness: silent. Only respond when explicitly invoked with the activation prefix 'Chat' (case-insensitive).";
          case DEFAULT -> "";
        };
    String transportInstruction =
        message != null && message.isLxmfTransport()
            ? "You are a chat assistant over LXMF on Reticulum. This transport currently supports one-on-one plain text only. Do not use reactions, attachments, generated images, group controls, or markdown. "
            : "You are a chat assistant for BlueChat. "
                + "You can use reactions for quick acknowledgements and avoid spamming. "
                + IMESSAGE_FORMATTING_INSTRUCTION;
    String publicAgentInstruction =
        "The public phone number for this agent is "
            + BBMessageAgent.AGENT_PHONE_NUMBER
            + ". When someone asks how this program works, how to try it, or how to contact the agent, mention that they can text this number. ";
    return EasyInputMessage.builder()
        .role(EasyInputMessage.Role.SYSTEM)
        .content(
            transportInstruction
                + publicAgentInstruction
                + (groupMessage
                    ? "Only respond when it is helpful or requested - this is a group message and not all messages are for you. You MUST ONLY respond if the message was directed to you or if your response will add useful and helpful information."
                    : "This is a one on one message with a user. You should respond to messages unless no reply is needed.")
                + "Never reply to your own messages."
                + responsivenessInstruction
                + "Use the "
                + MemoryGetAgentTool.TOOL_NAME
                + " tool when memory could improve your response (skip if no reply is needed or another tool is more appropriate). "
                + " Always ask the memory tool before directly asking the user to see if memory already has the answer to your question. "
                + "Send a natural language query to the tool describing what information may help you answer. "
                + "If no reply is needed, output exactly "
                + BBMessageAgent.NO_RESPONSE_TEXT
                + ".")
        .build();
  }

  private EasyInputMessage developerMessage(IncomingMessage message) {
    if (message != null && message.isLxmfTransport()) {
      return EasyInputMessage.builder()
          .role(EasyInputMessage.Role.DEVELOPER)
          .content(
              "You may respond with plain text if that is sufficient. "
                  + "All outgoing LXMF text must be plain text only. Do not use markdown or formatting markers such as **, __, backticks, or markdown lists. "
                  + "LXMF support is currently minimal: one-on-one text only. Do not try to send reactions, images, attachments, GIFs, group changes, or thread replies. "
                  + "Only call "
                  + SendTextAgentTool.TOOL_NAME
                  + " when you specifically need to send an extra message; plain text is fine otherwise. "
                  + "Use available tools for tasks like calendars, memory, Coder, scheduled follow-ups, or lookups when asked. "
                  + "When the user asks about quota, usage limits, daily messages, or remaining messages, call "
                  + GetUsageLimitsAgentTool.TOOL_NAME
                  + " before answering. "
                  + "If the user asks the assistant to be more or less responsive, call "
                  + AssistantResponsivenessAgentTool.TOOL_NAME
                  + " to update the setting. The silent mode will only invoke responses when the message starts with 'Chat' (case-insensitive). "
                  + "If a user shares their name, ask if it's okay to store it globally for future chats; only call "
                  + AssistantNameAgentTool.TOOL_NAME
                  + " after they explicitly agree. "
                  + "For Google Calendar requests, use the available calendar tools. If the account is not linked, call "
                  + ManageAccountsAgentTool.TOOL_NAME
                  + " to get an auth_url and have the user complete the OAuth flow in their browser. "
                  + "When the user shares information about themselves, or information that is helpful to remember, use the "
                  + MemorySaveAgentTool.TOOL_NAME
                  + " tool to persist that info. "
                  + feedbackInstruction()
                  + "If asked to recall details about the user or prior interactions, or if memory could help answer a question, call "
                  + MemoryGetAgentTool.TOOL_NAME
                  + " before responding. "
                  + "If no reply is needed, output exactly "
                  + BBMessageAgent.NO_RESPONSE_TEXT
                  + ".")
          .build();
    }
    return EasyInputMessage.builder()
        .role(EasyInputMessage.Role.DEVELOPER)
        .content(
            "You may respond with plain text if that is sufficient. "
                + IMESSAGE_FORMATTING_INSTRUCTION
                + "Only call "
                + SendTextAgentTool.TOOL_NAME
                + " or "
                + SendReactionAgentTool.TOOL_NAME
                + " when you specifically need those actions; plain text is fine otherwise. "
                + "Use "
                + SendPollAgentTool.TOOL_NAME
                + " when a user asks to make, create, start, or send a poll. Use "
                + ReadPollAgentTool.TOOL_NAME
                + " when asked to read poll results, count votes, summarize choices, or inspect a poll by message GUID. "
                + "When sending a text, you may optionally apply a BlueChat effect via the effect parameter, but use effects sparingly (e.g. happy_birthday for birthday wishes). "
                + "Use available tools for tasks like calendars or lookups when asked. "
                + "Use web_search for current info or external lookups when relevant. "
                + "When the user asks about quota, usage limits, daily messages, or remaining messages, call "
                + GetUsageLimitsAgentTool.TOOL_NAME
                + " before answering. "
                + "If the user requests an image and has attached images, use those images as starting references for image generation. "
                + "If the user asks the assistant to be more or less responsive (especially in group chats), call "
                + AssistantResponsivenessAgentTool.TOOL_NAME
                + " to update the setting. The silent mode will only invoke responses when the message starts with 'Chat' (case-insensitive). "
                + "If a user shares their name, ask if it's okay to store it globally for future chats; only call "
                + AssistantNameAgentTool.TOOL_NAME
                + " after they explicitly agree. "
                + "Use "
                + SearchConvoHistoryAgentTool.TOOL_NAME
                + " if you need to look up recent messages in this chat. "
                + "Use "
                + CurrentConversationInfoAgentTool.TOOL_NAME
                + " to see participants and metadata for the chat. "
                + "If the incoming message is part of a thread (replyToGuid or threadOriginatorGuid), reply in the same thread by setting selectedMessageGuid (and partIndex if provided). "
                + "Use "
                + GetThreadContextAgentTool.TOOL_NAME
                + " when asked about the last message or previously sent images in this thread. "
                + "Incoming poll vote or option updates may arrive as poll update notifications with current options and votes; reply with a concise user-visible poll update instead of "
                + BBMessageAgent.NO_RESPONSE_TEXT
                + ". "
                + feedbackInstruction()
                + "For group chats, you can rename the conversation or set a group icon when requested. "
                + "When the user asks to log in, sign up, manage their web account, connect the current chat identity to the website, or see linked integrations on the website, call "
                + LinkWebsiteAccountAgentTool.TOOL_NAME
                + " and send the returned user_facing_text. Do not invent account links manually. "
                + "Incoming message context may include websiteAccountLinked and websiteAccountExactChatLinked for the current sender or chat identity. When the user asks whether the current sender, current chat identity, or another sender is linked to a website account, call "
                + GetWebsiteAccountLinkStatusAgentTool.TOOL_NAME
                + " before answering if the context is absent, ambiguous, or the user names a different sender. "
                + "Use "
                + SendGiphyAgentTool.TOOL_NAME
                + " to reply with a GIF when it would be more expressive than text. "
                + "If a tool is unavailable, ask the user for clarification or say it is not configured. "
                + "For Google Calendar requests, use calendar tools like "
                + ListCalendarsAgentTool.TOOL_NAME
                + ", "
                + ListEventsAgentTool.TOOL_NAME
                + ", "
                + SearchEventsAgentTool.TOOL_NAME
                + ", "
                + GetEventAgentTool.TOOL_NAME
                + ", "
                + CreateEventAgentTool.TOOL_NAME
                + ", "
                + UpdateEventAgentTool.TOOL_NAME
                + ", "
                + DeleteEventAgentTool.TOOL_NAME
                + ", "
                + RespondToEventAgentTool.TOOL_NAME
                + ", "
                + GetFreebusyAgentTool.TOOL_NAME
                + ", "
                + ListColorsAgentTool.TOOL_NAME
                + ", and "
                + GetCurrentTimeAgentTool.TOOL_NAME
                + ". If the account is not linked, call "
                + ManageAccountsAgentTool.TOOL_NAME
                + " to get an auth_url and have the user complete the OAuth flow in their browser. "
                + "If multiple calendar accounts are linked, pass account_key (the account id from manage_accounts list, or 'default') to the calendar tools to pick the right account; ask if ambiguous. "
                + "Prefer taking action over asking for confirmation when the user's intent is clear and the action is reversible or low-risk; ask a clarifying question only when required information is missing or the action is destructive, expensive, or sensitive. "
                + "For multi-step tasks, keep using tools in the same turn until the task is complete, blocked by a specific error, or waiting on external work. "
                + "For long-running work, first start or advance the work with tools, then use "
                + ScheduledEventTool.TOOL_NAME
                + " to create a concrete follow-up instead of merely saying you will check later. Include enough identifiers and context in the scheduled task to continue without asking the user again. "
                + "When a scheduled follow-up checks async work and finds it is still pending or running, it must call "
                + ScheduledEventTool.TOOL_NAME
                + " again before ending the turn to create another one-time follow-up, unless the work is complete, failed, canceled, expired, or the task text's max attempts or deadline has been reached. Include the current attempt count, deadline or callback expiration, task id, callback id when available, original user intent, current status, and exact status/log tool to call next. Do not notify the user on every pending poll unless there is a useful change. "
                + "When a tool starts external work that may not finish immediately, such as a Coder task, Coder workspace build, deployment, test run, or log wait, you must call "
                + ScheduledEventTool.TOOL_NAME
                + " in the same turn after the start succeeds if the user expects results or monitoring. Use a one-time delaySeconds follow-up by default. "
                + "Use "
                + ScheduledEventListTool.TOOL_NAME
                + " to inspect pending follow-ups and "
                + ScheduledEventDeleteTool.TOOL_NAME
                + " to cancel them when requested. "
                + "When the user asks whether Coder is linked or says Coder tools are missing, call "
                + CoderAuthAgentTool.TOOL_NAME
                + " with status before answering; do not infer Coder availability from prior turns or static tool names. "
                + "When the user asks what Coder tools are available, answer from the currently available tool names whose names start with "
                + CoderMcpClient.TOOL_PREFIX
                + " plus "
                + StartCoderAsyncTaskAgentTool.TOOL_NAME
                + "; "
                + CoderAuthAgentTool.TOOL_NAME
                + " is only for auth/status/revoke, not Coder work. "
                + "When the user asks to start, run, kick off, or watch a Coder AI/dev task, call "
                + StartCoderAsyncTaskAgentTool.TOOL_NAME
                + " with the full task prompt. This one tool creates the callback, selects the task template, starts the Coder task, and schedules a fallback check; do not call "
                + WorkflowCallbackService.TOOL_NAME
                + ", "
                + StartCoderAsyncTaskAgentTool.CREATE_TASK_MCP_TOOL
                + ", or "
                + ScheduledEventTool.TOOL_NAME
                + " separately for initial Coder AI task startup. "
                + "For other Coder workspace, template, file, shell, status, or log requests, use available Coder MCP tools whose names start with "
                + CoderMcpClient.TOOL_PREFIX
                + ". If Coder is needed but no Coder task/tool path is available, call "
                + CoderAuthAgentTool.TOOL_NAME
                + " with auth_url and ask the user to complete the login link. "
                + "For multi-step Coder requests, keep using tool calls in the current turn until the requested action is complete or blocked by a specific error. "
                + "If a Coder tool returns a validation error and you have enough information to correct it, call the needed Coder tools and retry in the same turn. "
                + "After starting a long-running Coder workspace build or other non-task Coder work, use "
                + ScheduledEventTool.TOOL_NAME
                + " to check status/results later when the user expects you to watch it; include the task/workspace identifier, original request, callback id when available, maximum watch deadline, attempt count, which Coder status/log tools to call, and an instruction to call "
                + ScheduledEventTool.TOOL_NAME
                + " again if the Coder work is still pending or running. "
                + "Do not say a Coder action is done, starting, or being watched until the matching Coder tool has succeeded; only promise future watching if you have created an explicit follow-up mechanism. "
                + "When the user shares information about themselves, or information that is helpful to remember "
                + "use the "
                + MemorySaveAgentTool.TOOL_NAME
                + " tool to persist that info. "
                + "Use the current conversation identity; do not ask for an identifier. "
                + "If asked to recall details about the user or prior interactions, or if memory could help answer a question, "
                + "call "
                + MemoryGetAgentTool.TOOL_NAME
                + " before responding. "
                + "If the user asks to correct or remove saved details and provides a memory_id, "
                + "call "
                + MemoryUpdateAgentTool.TOOL_NAME
                + " or "
                + MemoryDeleteAgentTool.TOOL_NAME
                + ". "
                + "If no reply is needed, output exactly "
                + BBMessageAgent.NO_RESPONSE_TEXT
                + ". "
                + "If the incoming message starts with 'Reacted ', 'Loved ', 'Liked ', 'Disliked ', 'Questioned ', 'Emphasized ', 'Laughed at ' - reply "
                + BBMessageAgent.NO_RESPONSE_TEXT
                + " unless the reaction directly answers a question you (the assistant) asked or implies the user needs clarification. These are just reactions to your prior message and do not necessarily indicate a response is needed. Use your best judgement but err on the side of being less verbose and not responding by using "
                + BBMessageAgent.NO_RESPONSE_TEXT
                + ".")
        .build();
  }

  private String feedbackInstruction() {
    if (feedbackService == null) {
      return "";
    }
    return "When the incoming message is feedback about the assistant, model, tools, BlueChat, bugs, missing or desired capabilities, complaints, praise, or asks to pass something to the creator/owner, call "
        + FeedbackAgentTool.TOOL_NAME
        + " with the user's exact feedback. Also call it for capability feedback phrased as questions like 'can you do this?' or 'why can't you do this?' when the message is about what the assistant or tools can or should do. Continue to answer normally after recording when a reply is useful. ";
  }

  private EasyInputMessage userMessage(IncomingMessage message) {
    List<ResponseInputContent> content = new ArrayList<>();
    StringBuilder text = new StringBuilder();
    text.append("Incoming message");
    if (message.sender() != null && !message.sender().isBlank()) {
      text.append(" from ").append(message.sender());
    }
    if (message.sender() != null && !message.sender().isBlank()) {
      String knownName = profileService.getGlobalNameForMessage(message);
      if (knownName != null && !knownName.isBlank()) {
        text.append(" [sender name=").append(knownName).append("]");
      }
    }
    if (message.isGroup()) {
      text.append(" (group chat)");
    }
    if (message.chatGuid() != null && !message.chatGuid().isBlank()) {
      text.append(" [chatGuid=").append(message.chatGuid()).append("]");
    }
    appendWebsiteAccountLinkContext(text, message);
    if (message.messageGuid() != null && !message.messageGuid().isBlank()) {
      text.append(" [messageGuid=").append(message.messageGuid()).append("]");
    }
    if (message.threadOriginatorGuid() != null && !message.threadOriginatorGuid().isBlank()) {
      text.append(" [threadOriginatorGuid=").append(message.threadOriginatorGuid()).append("]");
    }
    if (message.associatedMessageGuid() != null && !message.associatedMessageGuid().isBlank()) {
      text.append(" [associatedMessageGuid=").append(message.associatedMessageGuid()).append("]");
    }
    if (message.replyToGuid() != null && !message.replyToGuid().isBlank()) {
      text.append(" [replyToGuid=").append(message.replyToGuid()).append("]");
    }
    if (message.balloonBundleId() != null && !message.balloonBundleId().isBlank()) {
      text.append(" [balloonBundleId=").append(message.balloonBundleId()).append("]");
    }
    if (resolveThreadRootGuid(message) != null) {
      text.append(" [threadReply=true]");
    }
    text.append(": ");
    if (message.text() != null && !message.text().isBlank()) {
      text.append(message.text());
    } else {
      text.append("[no text]");
    }
    AgentAttachmentInputBuilder.ResolvedAttachments attachments =
        attachmentInputBuilder.resolve(message);
    if (attachments.imageCount() > 0) {
      text.append(" [").append(attachments.imageCount()).append(" image(s) attached]");
    }
    if (attachments.fileCount() > 0) {
      text.append(" [").append(attachments.fileCount()).append(" file(s) attached]");
    }
    content.add(
        ResponseInputContent.ofInputText(
            ResponseInputText.builder().text(text.toString()).build()));
    content.addAll(attachments.inputContent());
    return EasyInputMessage.builder()
        .role(EasyInputMessage.Role.USER)
        .contentOfResponseInputMessageContentList(content)
        .build();
  }

  private void appendWebsiteAccountLinkContext(StringBuilder text, IncomingMessage message) {
    if (websiteAccountService == null || message == null) {
      return;
    }
    try {
      WebsiteAccountService.SenderLinkStatus status = websiteAccountService.getLinkStatus(message);
      if (status.accountId() == null || status.accountId().isBlank()) {
        return;
      }
      text.append(" [websiteAccountLinked=").append(status.linked()).append("]");
      text.append(" [websiteAccountExactChatLinked=").append(status.exactChatLinked()).append("]");
      if (status.modelAccess() != null) {
        text.append(" [modelPremium=").append(status.modelAccess().getIsPremium()).append("]");
        text.append(" [currentModel=").append(status.modelAccess().getCurrentModel()).append("]");
      }
    } catch (Exception e) {
      log.debug("Failed to load website account link context", e);
    }
  }

  private String resolveThreadRootGuid(IncomingMessage message) {
    if (message == null) {
      return null;
    }
    if (message.threadOriginatorGuid() != null && !message.threadOriginatorGuid().isBlank()) {
      return message.threadOriginatorGuid();
    }
    return null;
  }
}
