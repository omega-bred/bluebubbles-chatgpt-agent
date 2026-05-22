import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import type { AdminFeedbackItem, AdminFeedbackListResponse } from "../client";
import { AuthGate } from "../components/AuthGate";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { adminApi, type AdminFeedbackFilter } from "../services/api-client";
import { trackEvent } from "../services/analytics";

const numberFormat = new Intl.NumberFormat();
const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
  dateStyle: "medium",
  timeStyle: "short",
});
const filters: AdminFeedbackFilter[] = ["unread", "read", "all"];

type ApiDateValue = Date | number | string | null | undefined;

export function AdminFeedbackPage({ auth }: { auth: AuthState }) {
  const [status, setStatus] = React.useState<AdminFeedbackFilter>("unread");
  const [data, setData] = React.useState<AdminFeedbackListResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [updatingId, setUpdatingId] = React.useState<string | null>(null);

  const load = React.useCallback(async () => {
    if (!auth.authenticated || !auth.admin) {
      return;
    }
    setLoading(true);
    try {
      const feedback = await adminApi.listFeedback(status, 100);
      setData(feedback);
      trackEvent("web_admin_feedback_loaded", {
        filter: status,
        total_count: feedback.total_count || 0,
        unread_count: feedback.unread_count || 0,
      });
      setError(null);
    } catch (err) {
      const responseStatus = (err as { response?: { status?: number } })?.response?.status;
      trackEvent("web_admin_feedback_failed", { filter: status, status_code: responseStatus || 0 });
      setError(
        responseStatus === 403
          ? "Your token is missing the bbagent-admin-role role."
          : err instanceof Error
            ? err.message
            : "Unable to load feedback.",
      );
    } finally {
      setLoading(false);
    }
  }, [auth.admin, auth.authenticated, status]);

  React.useEffect(() => {
    void load();
  }, [load]);

  if (!auth.ready) {
    return <CenteredMessage title="Loading feedback" body="Getting your session ready." />;
  }

  if (!auth.authenticated) {
    return <AuthGate title="Admin feedback" />;
  }

  if (!auth.admin) {
    return (
      <CenteredMessage
        title="Admin access required"
        body="Sign in with an account that has bbagent-admin-role."
      />
    );
  }

  const items = data?.items || [];

  async function toggleRead(item: AdminFeedbackItem) {
    if (!item.feedback_id) {
      return;
    }
    setUpdatingId(item.feedback_id);
    try {
      const action = item.read_status === "unread" ? "mark_read" : "mark_unread";
      if (item.read_status === "unread") {
        await adminApi.markFeedbackRead(item.feedback_id);
      } else {
        await adminApi.markFeedbackUnread(item.feedback_id);
      }
      trackEvent("web_admin_feedback_action", { action });
      await load();
    } finally {
      setUpdatingId(null);
    }
  }

  return (
    <div className="account-shell admin-shell">
      <SiteNav auth={auth} />
      <main className="account-main admin-main">
        <section className="account-heading admin-heading">
          <p className="eyebrow">Admin</p>
          <h1>Feedback inbox</h1>
          <p>
            {formatCount(data?.unread_count)} unread / {formatCount(data?.total_count)} total
          </p>
          <nav className="admin-page-nav" aria-label="Admin pages">
            <a href="/admin">Statistics</a>
            <a className="active" href="/admin/feedback">
              Feedback
            </a>
          </nav>
        </section>

        <section className="admin-toolbar feedback-toolbar">
          <div className="segmented-control" aria-label="Feedback status">
            {filters.map((filter) => (
              <button
                className={status === filter ? "active" : ""}
                key={filter}
                onClick={() => {
                  trackEvent("web_admin_feedback_filter", { filter });
                  setStatus(filter);
                }}
              >
                {filterLabel(filter)}
              </button>
            ))}
          </div>
          <button className="button button-primary" disabled={loading} onClick={() => void load()}>
            {loading ? "Refreshing" : "Refresh"}
          </button>
        </section>

        {error ? <p className="error-banner">{error}</p> : null}

        <section className="admin-stat-grid feedback-stat-grid">
          <MetricTile label="Unread" value={formatCount(data?.unread_count)} />
          <MetricTile label="Read" value={formatCount(data?.read_count)} />
          <MetricTile label="Total" value={formatCount(data?.total_count)} />
        </section>

        <section className="feedback-inbox" aria-label="Feedback inbox">
          {items.length === 0 ? (
            <article className="empty-state">
              <h2>No feedback here.</h2>
              <p>The selected inbox is clear.</p>
            </article>
          ) : (
            items.map((item) => (
              <article
                className={`feedback-item ${item.read_status === "unread" ? "unread" : ""}`}
                key={item.feedback_id}
              >
                <header>
                  <div>
                    <p className="eyebrow">{formatCategory(item.category)}</p>
                    <h2>Account #{item.account_bucket || "unknown"}</h2>
                  </div>
                  <span className="feedback-status">{statusLabel(item.read_status)}</span>
                </header>
                <p className="feedback-text">{item.feedback_text}</p>
                <div className="feedback-meta">
                  <span>{formatDateTime(item.submitted_at)}</span>
                  <span>{item.transport || "unknown transport"}</span>
                  {item.sender ? <span title={item.sender}>Sender {item.sender}</span> : null}
                  {item.chat_guid ? <span title={item.chat_guid}>Chat {item.chat_guid}</span> : null}
                  {item.message_guid ? (
                    <span title={item.message_guid}>Message {item.message_guid}</span>
                  ) : null}
                </div>
                <footer>
                  <span title={item.account_id}>Canonical account {item.account_id}</span>
                  <button
                    className="button button-secondary compact"
                    disabled={updatingId === item.feedback_id}
                    onClick={() => void toggleRead(item)}
                  >
                    {item.read_status === "unread" ? "Mark read" : "Mark unread"}
                  </button>
                </footer>
              </article>
            ))
          )}
        </section>
      </main>
    </div>
  );
}

function MetricTile({ label, value }: { label: string; value: string }) {
  return (
    <article className="metric-tile">
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

function filterLabel(filter: AdminFeedbackFilter): string {
  return filter[0].toUpperCase() + filter.slice(1);
}

function statusLabel(value: string | undefined): string {
  return value === "read" ? "Read" : "Unread";
}

function formatCategory(value: string | undefined): string {
  if (!value) {
    return "General";
  }
  return value
    .split(/[_-]/)
    .filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join(" ");
}

function formatCount(value: number | undefined): string {
  return numberFormat.format(value || 0);
}

function formatDateTime(value: ApiDateValue): string {
  if (value === null || value === undefined || value === "") {
    return "";
  }
  const date = value instanceof Date ? value : new Date(value);
  return Number.isFinite(date.getTime()) ? dateTimeFormat.format(date) : "";
}
