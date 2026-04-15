import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import type { WebsiteIntegrationSummary, WebsiteLinkedAccountsResponse } from "../client";
import { AuthGate } from "../components/AuthGate";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { websiteAccountApi } from "../services/api-client";
import { displayModelLabel } from "../utils/model-label";

export function AccountPage({ auth }: { auth: AuthState }) {
  const [data, setData] = React.useState<WebsiteLinkedAccountsResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(false);

  const load = React.useCallback(async () => {
    if (!auth.authenticated) {
      return;
    }
    setLoading(true);
    try {
      setData(await websiteAccountApi.listLinkedAccounts());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to load linked accounts.");
    } finally {
      setLoading(false);
    }
  }, [auth.authenticated]);

  React.useEffect(() => {
    void load();
  }, [load]);

  if (!auth.ready) {
    return <CenteredMessage title="Loading account" body="Getting your session ready." />;
  }

  if (!auth.authenticated) {
    return <AuthGate title="Manage your account" />;
  }

  return (
    <div className="account-shell">
      <SiteNav auth={auth} />
      <main className="account-main">
        <section className="account-heading">
          <p className="eyebrow">Account</p>
          <h1>
            {data?.account?.display_name || data?.account?.preferred_username || "Your account"}
          </h1>
          <p>{data?.account?.email || "Signed in with Keycloak."}</p>
        </section>

        {error ? <p className="error-banner">{error}</p> : null}
        {loading ? <p className="muted">Loading linked accounts.</p> : null}

        <section className="linked-list">
          {(data?.integrations || []).length === 0 ? (
            <EmptyLinks />
          ) : (
            data?.integrations?.map((integration) => (
              <LinkedIdentity
                key={integration.link?.link_id}
                integration={integration}
                onDeleted={load}
              />
            ))
          )}
        </section>
      </main>
    </div>
  );
}

function LinkedIdentity({
  integration,
  onDeleted,
}: {
  integration: WebsiteIntegrationSummary;
  onDeleted: () => Promise<void>;
}) {
  const [deleting, setDeleting] = React.useState(false);
  const link = integration.link;
  if (!link) {
    return null;
  }
  const calendars = integration.gcal_accounts || [];
  const modelAccess = integration.model_access;
  const modelLabel = modelAccess ? displayModelLabel(modelAccess.current_model_label) : "";
  const planLabel = modelAccess?.is_premium ? "Premium" : "Free";
  const accessNote =
    modelAccess && modelAccess.is_premium
      ? `${planLabel} account · ${modelLabel}`
      : `${planLabel} account`;
  return (
    <article className="linked-item">
      <div>
        <p className="eyebrow">iMessage sender</p>
        <h2>{link.is_group ? "Group iMessage chat" : "Direct iMessage sender"}</h2>
        <p className="muted">{link.service || "iMessage"} linked to this account.</p>
        {modelAccess ? (
          <p className="model-note">
            {accessNote}
            {modelAccess.model_selection_configurable ? "" : " · read only"}
          </p>
        ) : null}
      </div>
      <div className="integration-pills">
        {modelAccess ? (
          <span className={modelAccess.is_premium ? "pill premium" : "pill"}>
            {modelLabel === "Free" ? "Free" : `Model ${modelLabel}`}
          </span>
        ) : null}
        <span className={integration.coder_linked ? "pill good" : "pill"}>
          Coder {integration.coder_linked ? "linked" : "not linked"}
        </span>
        <span className={calendars.length > 0 ? "pill good" : "pill"}>
          Google Calendar{" "}
          {calendars.length > 0
            ? calendars.map((item) => item.account_id).join(", ")
            : "not linked"}
        </span>
      </div>
      <button
        className="button button-secondary"
        disabled={deleting}
        onClick={async () => {
          setDeleting(true);
          await websiteAccountApi.deleteLink(link.link_id);
          await onDeleted();
          setDeleting(false);
        }}
      >
        {deleting ? "Unlinking" : "Unlink sender"}
      </button>
    </article>
  );
}

function EmptyLinks() {
  return (
    <article className="empty-state">
      <h2>No iMessage senders linked yet.</h2>
      <p>
        Text the bot and ask it to link your website account. It will send a short-lived login link
        back in iMessage.
      </p>
    </article>
  );
}
