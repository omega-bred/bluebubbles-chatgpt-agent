import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import type {
  ConversationResponsivenessOption,
  ConversationSettingsResponse,
  ConversationSettingsUpdateRequest,
  ConversationCatchupPreferencesUpdateRequest,
} from "../client";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { appClipApi, conversationSettingsApi } from "../services/api-client";
import { trackEvent } from "../services/analytics";

export function ConversationSettingsPage({ auth }: { auth: AuthState }) {
  const started = React.useRef(false);
  const [settings, setSettings] = React.useState<ConversationSettingsResponse | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [saving, setSaving] = React.useState<string | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [catchupTimezone, setCatchupTimezone] = React.useState("UTC");
  const [catchupQuietStart, setCatchupQuietStart] = React.useState("22:00");
  const [catchupQuietEnd, setCatchupQuietEnd] = React.useState("08:00");

  React.useEffect(() => {
    if (started.current) {
      return;
    }
    started.current = true;

    async function load() {
      try {
        const url = new URL(window.location.href);
        const token = url.searchParams.get("token");
        if (token) {
          url.searchParams.delete("token");
          window.history.replaceState(window.history.state, "", url);
        }
        const session = token
          ? await appClipApi.createSession(token)
          : await appClipApi.getSession();
        if (session.purpose !== "conversation_settings") {
          throw new Error("This link opens account settings, not conversation settings.");
        }
        setSettings(session.conversation_settings || await conversationSettingsApi.get());
        trackEvent("web_conversation_settings_loaded", {
          launch_source: token ? "link" : "stored",
        });
        void appClipApi.trackEvent({
          event_name: "web_conversation_settings_loaded",
          properties: { launch_source: token ? "link" : "stored" },
        });
      } catch (err) {
        trackEvent("web_conversation_settings_load_failed");
        setError(err instanceof Error ? err.message : "Unable to load conversation settings.");
      } finally {
        setLoading(false);
      }
    }

    void load();
  }, []);

  React.useEffect(() => {
    if (!settings) {
      return;
    }
    setCatchupTimezone(settings.personal_catchups.timezone);
    setCatchupQuietStart(settings.personal_catchups.quiet_start);
    setCatchupQuietEnd(settings.personal_catchups.quiet_end);
  }, [settings]);

  const updateResponsiveness = async (responsiveness: string) => {
    if (!settings) {
      return;
    }
    setSaving(responsiveness);
    setError(null);
    trackEvent("web_conversation_responsiveness_start", { responsiveness });
    try {
      const response = await conversationSettingsApi.updateResponsiveness(
        responsiveness as ConversationSettingsUpdateRequest["responsiveness"],
      );
      setSettings(response.settings);
      trackEvent("web_conversation_responsiveness_updated", { responsiveness });
      void appClipApi.trackEvent({
        event_name: "web_conversation_responsiveness_updated",
        properties: { responsiveness },
      });
    } catch (err) {
      trackEvent("web_conversation_responsiveness_failed", { responsiveness });
      setError(err instanceof Error ? err.message : "Unable to update conversation settings.");
    } finally {
      setSaving(null);
    }
  };

  const updateGroupMemory = async (enabled: boolean) => {
    if (!settings) {
      return;
    }
    setSaving("group-memory");
    setError(null);
    trackEvent("web_group_memory_update_start", { enabled });
    try {
      const response = await conversationSettingsApi.updateGroupMemory(
        enabled,
      );
      setSettings(response.settings);
      trackEvent("web_group_memory_updated", { enabled });
      void appClipApi.trackEvent({
        event_name: "web_group_memory_updated",
        properties: { enabled: String(enabled) },
      });
    } catch (err) {
      trackEvent("web_group_memory_update_failed", { enabled });
      setError(err instanceof Error ? err.message : "Unable to update group memory.");
    } finally {
      setSaving(null);
    }
  };

  const updateCatchups = async (enabled: boolean) => {
    if (!settings) {
      return;
    }
    const request: ConversationCatchupPreferencesUpdateRequest = {
      enabled,
      timezone: catchupTimezone,
      quiet_start: catchupQuietStart,
      quiet_end: catchupQuietEnd,
    };
    setSaving("personal-catchups");
    setError(null);
    trackEvent("web_personal_catchups_update_start", { enabled });
    try {
      const response = await conversationSettingsApi.updateCatchups(
        request,
      );
      setSettings(response.settings);
      trackEvent("web_personal_catchups_updated", { enabled });
      void appClipApi.trackEvent({
        event_name: "web_personal_catchups_updated",
        properties: { enabled: String(enabled) },
      });
    } catch (err) {
      trackEvent("web_personal_catchups_update_failed", { enabled });
      setError(err instanceof Error ? err.message : "Unable to update personal catch-ups.");
    } finally {
      setSaving(null);
    }
  };

  if (loading && !settings) {
    return <CenteredMessage title="Loading settings" body="Getting this conversation ready." />;
  }

  const conversation = settings?.conversation;
  const options = settings?.options?.filter((option) => option.enabled) || [];

  return (
    <div className="account-shell conversation-settings-shell">
      <SiteNav auth={auth} />
      <main className="account-main conversation-settings-main">
        <section className="conversation-settings-hero">
          <div className="conversation-avatar" aria-hidden="true">
            {conversation?.icon_url ? (
              <img src={conversation.icon_url} alt="" />
            ) : (
              <span>{conversationInitial(conversation?.display_name)}</span>
            )}
          </div>
          <div>
            <p className="eyebrow">Conversation Settings</p>
            <h1>{conversation?.display_name || "BlueChat conversation"}</h1>
            <p>
              {conversationSubtitle(
                conversation?.chat_identifier,
                conversation?.participant_count,
              )}
            </p>
          </div>
        </section>

        {error ? <p className="error-banner">{error}</p> : null}

        {settings ? (
          <section className="conversation-settings-grid">
            <article className="conversation-responsiveness-panel wide-panel">
              <div>
                <p className="eyebrow">Assistant Behavior</p>
                <h2>{settings.current_responsiveness_label}</h2>
                <p className="muted">
                  Choose how often BlueChatAI should participate in this specific conversation.
                </p>
              </div>
              <div className="responsiveness-options" aria-busy={Boolean(saving)}>
                {options.map((option) => (
                  <ResponsivenessOptionButton
                    key={option.responsiveness}
                    option={option}
                    selected={option.responsiveness === settings.current_responsiveness}
                    busy={saving === option.responsiveness}
                    disabled={Boolean(saving)}
                    onSelect={updateResponsiveness}
                  />
                ))}
              </div>
            </article>

            {settings.group_memory.available ? (
              <article className="conversation-info-panel">
                <p className="eyebrow">Collective Context</p>
                <h2>{settings.group_memory.label}</h2>
                <p className="muted">{settings.group_memory.description}</p>
                <InfoLine
                  label="Status"
                  value={settings.group_memory.enabled ? "On" : "Off"}
                />
                {settings.group_memory.collection_started_at ? (
                  <InfoLine
                    label="Collection started"
                    value={formatDateTime(settings.group_memory.collection_started_at)}
                  />
                ) : null}
                <button
                  type="button"
                  className="responsiveness-option"
                  disabled={Boolean(saving)}
                  onClick={() => void updateGroupMemory(!settings.group_memory.enabled)}
                >
                  <span>{settings.group_memory.enabled ? "Turn memory off" : "Turn memory on"}</span>
                  <small>
                    {saving === "group-memory"
                      ? "Saving"
                      : "The change will be announced in this group."}
                  </small>
                </button>
              </article>
            ) : null}

            <article className="conversation-info-panel">
              <p className="eyebrow">Personal Catch-ups</p>
              <h2>
                {settings.personal_catchups.available
                  ? settings.personal_catchups.enabled
                    ? "On"
                    : "Off"
                  : "Unavailable"}
              </h2>
              <p className="muted">
                {settings.personal_catchups.available
                  ? "Receive developments since your last catch-up in your personal chat."
                  : "Personal summaries require group memory and current membership."}
              </p>
              {settings.personal_catchups.available ? (
                <div className="conversation-catchup-controls">
                  <label>
                    <span>Timezone</span>
                    <input
                      type="text"
                      value={catchupTimezone}
                      placeholder="America/Los_Angeles"
                      disabled={Boolean(saving)}
                      onChange={(event) => setCatchupTimezone(event.target.value)}
                    />
                  </label>
                  <label>
                    <span>Quiet start</span>
                    <input
                      type="time"
                      value={catchupQuietStart}
                      disabled={Boolean(saving)}
                      onChange={(event) => setCatchupQuietStart(event.target.value)}
                    />
                  </label>
                  <label>
                    <span>Quiet end</span>
                    <input
                      type="time"
                      value={catchupQuietEnd}
                      disabled={Boolean(saving)}
                      onChange={(event) => setCatchupQuietEnd(event.target.value)}
                    />
                  </label>
                  <button
                    type="button"
                    className="responsiveness-option"
                    disabled={Boolean(saving)}
                    onClick={() =>
                      void updateCatchups(!settings.personal_catchups.enabled)
                    }
                  >
                    <span>
                      {settings.personal_catchups.enabled
                        ? "Turn personal catch-ups off"
                        : "Turn personal catch-ups on"}
                    </span>
                    <small>
                      {saving === "personal-catchups"
                        ? "Saving"
                        : "At most one summary per day for this group."}
                    </small>
                  </button>
                  {settings.personal_catchups.enabled ? (
                    <button
                      type="button"
                      className="responsiveness-option"
                      disabled={Boolean(saving)}
                      onClick={() => void updateCatchups(true)}
                    >
                      <span>Save quiet hours</span>
                      <small>Use an IANA timezone such as America/Los_Angeles.</small>
                    </button>
                  ) : null}
                </div>
              ) : null}
            </article>

            <article className="conversation-info-panel">
              <p className="eyebrow">Conversation</p>
              <h2>{conversation?.is_group ? "Group chat" : "Direct chat"}</h2>
              <InfoLine label="Participants" value={formatParticipantCount(conversation?.participant_count)} />
              {conversation?.chat_identifier ? (
                <InfoLine label="Identifier" value={conversation.chat_identifier} />
              ) : null}
            </article>

            <article className="conversation-info-panel">
              <p className="eyebrow">People</p>
              <h2>Linked participants</h2>
              {(conversation?.participants || []).length > 0 ? (
                <div className="conversation-participant-list">
                  {(conversation?.participants || []).slice(0, 8).map((participant) => (
                    <span key={participant.address}>{participant.address}</span>
                  ))}
                </div>
              ) : (
                <p className="muted">Participant details are not available for this chat.</p>
              )}
            </article>
          </section>
        ) : (
          <CenteredMessage
            title="No settings link"
            body="Ask BlueChatAI to send a conversation settings link from Messages."
          />
        )}
      </main>
    </div>
  );
}

function ResponsivenessOptionButton({
  option,
  selected,
  busy,
  disabled,
  onSelect,
}: {
  option: ConversationResponsivenessOption;
  selected: boolean;
  busy: boolean;
  disabled: boolean;
  onSelect: (responsiveness: string) => Promise<void>;
}) {
  return (
    <button
      type="button"
      className={selected ? "responsiveness-option selected" : "responsiveness-option"}
      disabled={disabled || selected}
      onClick={() => void onSelect(option.responsiveness)}
    >
      <span>{option.label}</span>
      <small>{busy ? "Saving" : option.description}</small>
    </button>
  );
}

function InfoLine({ label, value }: { label: string; value: string }) {
  return (
    <div className="conversation-info-line">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function conversationInitial(name?: string) {
  return (name || "B").trim().slice(0, 1).toUpperCase();
}

function conversationSubtitle(identifier?: string, participantCount?: number) {
  const count = formatParticipantCount(participantCount);
  return [identifier, count].filter(Boolean).join(" · ");
}

function formatParticipantCount(value?: number) {
  const count = Number(value || 0);
  if (!Number.isFinite(count) || count <= 0) {
    return "Participants unavailable";
  }
  return count === 1 ? "1 participant" : `${count} participants`;
}

function formatDateTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}
