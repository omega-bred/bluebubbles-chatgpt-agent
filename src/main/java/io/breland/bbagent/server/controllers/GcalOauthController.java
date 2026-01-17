package io.breland.bbagent.server.controllers;

import io.breland.bbagent.generated.api.GcalApiController;
import io.breland.bbagent.generated.bluebubblesclient.model.ApiV1MessageTextPostRequest;
import io.breland.bbagent.generated.model.GcalCalendarAccessEntry;
import io.breland.bbagent.generated.model.GcalCalendarAccessRequest;
import io.breland.bbagent.generated.model.GcalCalendarAccessResponse;
import io.breland.bbagent.generated.model.GcalCalendarInfo;
import io.breland.bbagent.generated.model.GcalCalendarListResponse;
import io.breland.bbagent.server.agent.BBHttpClientWrapper;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient;
import io.breland.bbagent.server.agent.tools.gcal.GcalClient.CalendarAccessMode;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;

@RestController
@RequestMapping("${openapi.blueBubblesChatGPTAgentOpenAPISpec.base-path:}")
public class GcalOauthController extends GcalApiController {
  private final GcalClient gcalClient;
  private final BBHttpClientWrapper bbHttpClientWrapper;

  public GcalOauthController(
      NativeWebRequest request, GcalClient gcalClient, BBHttpClientWrapper bbHttpClientWrapper) {
    super(request);
    this.gcalClient = gcalClient;
    this.bbHttpClientWrapper = bbHttpClientWrapper;
  }

  @Override
  public ResponseEntity<String> gcalCompleteOauth(String code, String state) {
    if (code == null || code.isBlank() || state == null || state.isBlank()) {
      return htmlResponse(
          HttpStatus.BAD_REQUEST,
          "Missing required OAuth parameters. Please try again.",
          null,
          false);
    }
    if (!gcalClient.isConfigured()) {
      return htmlResponse(
          HttpStatus.INTERNAL_SERVER_ERROR, "Google Calendar is not configured.", null, false);
    }
    Optional<GcalClient.OauthState> oauthState = gcalClient.parseOauthState(state);
    if (oauthState.isEmpty()) {
      return htmlResponse(
          HttpStatus.BAD_REQUEST,
          "Invalid OAuth state. Please retry the linking flow.",
          null,
          false);
    }
    boolean success = gcalClient.exchangeCode(oauthState.get().accountKey(), code);
    if (!success) {
      sendFollowup(
          oauthState.get().chatGuid(),
          oauthState.get().messageGuid(),
          "Calendar linking failed. Please try again.");
      return htmlResponse(
          HttpStatus.INTERNAL_SERVER_ERROR, "OAuth failed. Please try again.", null, false);
    }
    sendFollowup(
        oauthState.get().chatGuid(),
        oauthState.get().messageGuid(),
        "Calendar successfully linked.");
    return htmlResponse(
        HttpStatus.OK, "Google Calendar linked.", oauthState.get().accountKey(), true);
  }

  @Override
  public ResponseEntity<GcalCalendarListResponse> gcalListCalendars(String accountKey) {
    if (accountKey == null || accountKey.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    if (!gcalClient.isConfigured()) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
    try {
      var calendars = gcalClient.listCalendars(accountKey);
      var accessMap = gcalClient.getCalendarAccessMap(accountKey);
      GcalCalendarListResponse response = new GcalCalendarListResponse();
      response.setAccountKey(accountKey);
      response.setCalendars(
          calendars.stream()
              .map(
                  entry -> {
                    GcalCalendarInfo info = new GcalCalendarInfo();
                    info.setCalendarId(entry.calendarId());
                    info.setSummary(entry.summary());
                    info.setPrimary(entry.primary());
                    info.setTimeZone(entry.timeZone());
                    info.setAccessRole(entry.accessRole());
                    CalendarAccessMode mode = accessMap.getOrDefault(info.getCalendarId(), null);
                    if (mode != null) {
                      info.setSelected(true);
                      info.setMode(toApiMode(mode));
                    } else {
                      info.setSelected(false);
                      info.setMode(null);
                    }
                    return info;
                  })
              .collect(Collectors.toList()));
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  @Override
  public ResponseEntity<GcalCalendarAccessResponse> gcalSaveCalendarAccess(
      GcalCalendarAccessRequest requestBody) {
    if (requestBody == null
        || requestBody.getAccountKey() == null
        || requestBody.getAccountKey().isBlank()
        || requestBody.getCalendars() == null) {
      return ResponseEntity.badRequest().build();
    }
    try {
      gcalClient.saveCalendarAccess(
          requestBody.getAccountKey(),
          requestBody.getCalendars().stream().map(this::fromApiEntry).collect(Collectors.toList()));
      GcalCalendarAccessResponse response = new GcalCalendarAccessResponse();
      response.setStatus("ok");
      return ResponseEntity.ok(response);
    } catch (Exception e) {
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  private void sendFollowup(String chatGuid, String messageGuid, String message) {
    if (chatGuid == null || chatGuid.isBlank() || message == null || message.isBlank()) {
      return;
    }
    ApiV1MessageTextPostRequest request = new ApiV1MessageTextPostRequest();
    request.setChatGuid(chatGuid);
    request.setMessage(message);
    if (messageGuid != null && !messageGuid.isBlank()) {
      request.setSelectedMessageGuid(messageGuid);
      request.setPartIndex(0);
    }
    bbHttpClientWrapper.sendTextDirect(request);
  }

  private ResponseEntity<String> htmlResponse(
      HttpStatus status, String message, String accountKey, boolean showCalendarPicker) {
    String safeMessage = escapeHtml(message);
    String safeAccountKey = accountKey == null ? "" : escapeHtml(accountKey);
    String body =
        "<!doctype html>"
            + "<html lang=\"en\">"
            + "<head>"
            + "<meta charset=\"utf-8\"/>"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"/>"
            + "<title>Google Calendar OAuth</title>"
            + "<style>"
            + "body{font-family:system-ui,-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;margin:24px;line-height:1.4}"
            + ".container{max-width:720px;margin:0 auto}"
            + ".card{border:1px solid #ddd;border-radius:8px;padding:16px;margin-top:16px}"
            + "table{width:100%;border-collapse:collapse}"
            + "th,td{padding:8px;border-bottom:1px solid #eee;text-align:left}"
            + "select:disabled{opacity:.6}"
            + ".actions{margin-top:12px;display:flex;gap:8px;align-items:center}"
            + ".status{margin-top:8px;font-size:0.95em}"
            + "</style>"
            + "</head>"
            + "<body>"
            + "<div class=\"container\">"
            + "<h2>Google Calendar</h2>"
            + "<p>"
            + safeMessage
            + "</p>";
    if (showCalendarPicker && accountKey != null && !accountKey.isBlank()) {
      body +=
          "<div class=\"card\" data-account-key=\""
              + safeAccountKey
              + "\">"
              + "<h3>Calendar access</h3>"
              + "<p>Select which calendars the assistant can access, and set an access mode.</p>"
              + "<div id=\"calendar-root\">Loading calendars…</div>"
              + "<div class=\"actions\">"
              + "<button id=\"save-btn\" type=\"button\">Save</button>"
              + "<span id=\"save-status\" class=\"status\"></span>"
              + "</div>"
              + "</div>"
              + "<script>"
              + "const root=document.getElementById('calendar-root');"
              + "const card=document.querySelector('[data-account-key]');"
              + "const accountKey=card.getAttribute('data-account-key');"
              + "const statusEl=document.getElementById('save-status');"
              + "const saveBtn=document.getElementById('save-btn');"
              + "function renderTable(items){"
              + "const rows=items.map(item=>{"
              + "const checked=item.selected? 'checked' : '';"
              + "const mode=item.mode||'read_only';"
              + "return `<tr>"
              + "<td><input type=\"checkbox\" data-cal-id=\"${item.calendar_id}\" ${checked}></td>"
              + "<td>${item.summary||item.calendar_id}</td>"
              + "<td><select data-mode-for=\"${item.calendar_id}\" ${item.selected? '':'disabled'}>"
              + "<option value=\"read_only\" ${mode==='read_only'?'selected':''}>Read-only</option>"
              + "<option value=\"free_busy\" ${mode==='free_busy'?'selected':''}>Free/busy only</option>"
              + "<option value=\"full\" ${mode==='full'?'selected':''}>Full access</option>"
              + "</select></td>"
              + "</tr>`;"
              + "}).join('');"
              + "root.innerHTML=`<table>"
              + "<thead><tr><th>Allow</th><th>Calendar</th><th>Mode</th></tr></thead>"
              + "<tbody>${rows}</tbody></table>`;"
              + "root.querySelectorAll('input[type=checkbox]').forEach(cb=>{"
              + "cb.addEventListener('change',()=>{"
              + "const sel=root.querySelector(`select[data-mode-for='${cb.dataset.calId}']`);"
              + "if(sel){sel.disabled=!cb.checked;}"
              + "});"
              + "});"
              + "}"
              + "fetch(`/api/v1/gcal/calendars.gcal?account_key=${encodeURIComponent(accountKey)}`)"
              + ".then(r=>r.json())"
              + ".then(data=>renderTable(data.calendars||[]))"
              + ".catch(()=>{root.textContent='Failed to load calendars.';});"
              + "saveBtn.addEventListener('click',()=>{"
              + "const selections=[];"
              + "root.querySelectorAll('input[type=checkbox]').forEach(cb=>{"
              + "if(cb.checked){"
              + "const modeEl=root.querySelector(`select[data-mode-for='${cb.dataset.calId}']`);"
              + "const mode=modeEl?modeEl.value:'read_only';"
              + "selections.push({calendar_id:cb.dataset.calId,mode});"
              + "}"
              + "});"
              + "statusEl.textContent='Saving…';"
              + "fetch('/api/v1/gcal/calendarAccess.gcal',{"
              + "method:'POST',"
              + "headers:{'Content-Type':'application/json'},"
              + "body:JSON.stringify({account_key:accountKey,calendars:selections})"
              + "}).then(r=>{"
              + "if(!r.ok){throw new Error('save');}"
              + "return r.json();"
              + "}).then(()=>{statusEl.textContent='Saved.';})"
              + ".catch(()=>{statusEl.textContent='Save failed.';});"
              + "});"
              + "</script>";
    }
    body += "</div></body></html>";
    return ResponseEntity.status(status).contentType(MediaType.TEXT_HTML).body(body);
  }

  private GcalClient.CalendarAccess fromApiEntry(GcalCalendarAccessEntry entry) {
    CalendarAccessMode mode = CalendarAccessMode.fromApiValue(entry.getMode());
    return new GcalClient.CalendarAccess(entry.getCalendarId(), mode);
  }

  private io.breland.bbagent.generated.model.GcalCalendarAccessMode toApiMode(
      CalendarAccessMode mode) {
    return switch (mode) {
      case FULL -> io.breland.bbagent.generated.model.GcalCalendarAccessMode.FULL;
      case READ_ONLY -> io.breland.bbagent.generated.model.GcalCalendarAccessMode.READ_ONLY;
      case FREE_BUSY -> io.breland.bbagent.generated.model.GcalCalendarAccessMode.FREE_BUSY;
    };
  }

  private String escapeHtml(String input) {
    if (input == null) {
      return "";
    }
    return input
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
