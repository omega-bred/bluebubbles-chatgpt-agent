import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import type {
  AppClipSessionResponse,
  ConversationResponsivenessOption,
  ConversationSettingsResponse,
  ConversationSettingsUpdateRequest,
} from "../client";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { appClipApi, conversationSettingsApi } from "../services/api-client";
import { trackEvent } from "../services/analytics";

const SESSION_TOKEN_KEY = "bluechat.conversationSettings.sessionToken";

export function ConversationSettingsPage({ auth }: { auth: AuthState }) {
  const [session, setSession] = React.useState<AppClipSessionResponse | null>(null);
  const [settings, setSettings] = React.useState<ConversationSettingsResponse | null>(null);
  const [loading, setLoading] = React.useState(true);
  const [saving, setSaving] = React.useState<string | null>(null);
  const [error, setError] = React.useState<string | null>(null);

  const rememberSession = React.useCallback((nextSession: AppClipSessionResponse) => {
    window.sessionStorage.setItem(SESSION_TOKEN_KEY, nextSession.session_token);
    setSession(nextSession);
    setSettings(nextSession.conversation_settings || null);
  }, []);

  const load = React.useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const token = new URLSearchParams(window.location.search).get("token");
      let nextSession: AppClipSessionResponse;
      if (token) {
        nextSession = await appClipApi.createSession(token);
      } else {
        const storedToken = window.sessionStorage.getItem(SESSION_TOKEN_KEY);
        if (!storedToken) {
          throw new Error("Open a BlueChatAI conversation settings link from Messages.");
        }
        nextSession = await appClipApi.getSession(storedToken);
      }
      if (nextSession.purpose !== "conversation_settings") {
        throw new Error("This link opens account settings, not conversation settings.");
      }
      rememberSession(nextSession);
      if (!nextSession.conversation_settings) {
        setSettings(await conversationSettingsApi.get(nextSession.session_token));
      }
      trackEvent("web_conversation_settings_loaded", {
        launch_source: token ? "link" : "stored",
      });
      void appClipApi.trackEvent(nextSession.session_token, {
        event_name: "web_conversation_settings_loaded",
        properties: { launch_source: token ? "link" : "stored" },
      });
    } catch (err) {
      window.sessionStorage.removeItem(SESSION_TOKEN_KEY);
      trackEvent("web_conversation_settings_load_failed");
      setError(err instanceof Error ? err.message : "Unable to load conversation settings.");
    } finally {
      setLoading(false);
    }
  }, [rememberSession]);

  React.useEffect(() => {
    void load();
  }, [load]);

  const updateResponsiveness = async (responsiveness: string) => {
    if (!session) {
      return;
    }
    setSaving(responsiveness);
    setError(null);
    trackEvent("web_conversation_responsiveness_start", { responsiveness });
    try {
      const response = await conversationSettingsApi.updateResponsiveness(
        session.session_token,
        responsiveness as ConversationSettingsUpdateRequest["responsiveness"],
      );
      setSettings(response.settings);
      setSession({ ...session, conversation_settings: response.settings });
      trackEvent("web_conversation_responsiveness_updated", { responsiveness });
      void appClipApi.trackEvent(session.session_token, {
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
