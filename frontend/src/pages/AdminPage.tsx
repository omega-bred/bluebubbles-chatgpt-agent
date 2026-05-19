import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import type {
  AdminRateLimitUsage,
  AdminRateLimitUsageResponse,
  AdminSenderStats,
  AdminStatsBucket,
  AdminStatsResponse,
  AdminToolAccountTypeStats,
  AdminToolStats,
} from "../client";
import { AuthGate } from "../components/AuthGate";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { adminApi } from "../services/api-client";
import {
  formatBucket,
  formatCount,
  formatDateTime,
  formatDurationMs,
  formatPercent,
  formatTitleLabel,
  toIso,
  toLocalInputValue,
} from "../utils/admin-format";

export function AdminPage({ auth }: { auth: AuthState }) {
  const [fromInput, setFromInput] = React.useState(() =>
    toLocalInputValue(new Date(Date.now() - 24 * 60 * 60 * 1000)),
  );
  const [toInput, setToInput] = React.useState(() => toLocalInputValue(new Date()));
  const [data, setData] = React.useState<AdminStatsResponse | null>(null);
  const [limitData, setLimitData] = React.useState<AdminRateLimitUsageResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(false);

  const load = React.useCallback(async () => {
    if (!auth.authenticated || !auth.admin || !fromInput || !toInput) {
      return;
    }
    setLoading(true);
    try {
      const [stats, limits] = await Promise.all([
        adminApi.getStatistics(toIso(fromInput), toIso(toInput)),
        adminApi.getRateLimitUsage(),
      ]);
      setData(stats);
      setLimitData(limits);
      setError(null);
    } catch (err) {
      const status = (err as { response?: { status?: number } })?.response?.status;
      setError(
        status === 403
          ? "Your token is missing the bbagent-admin-role role."
          : err instanceof Error
            ? err.message
            : "Unable to load admin statistics.",
      );
    } finally {
      setLoading(false);
    }
  }, [auth.admin, auth.authenticated, fromInput, toInput]);

  React.useEffect(() => {
    void load();
  }, [load]);

  if (!auth.ready) {
    return <CenteredMessage title="Loading admin" body="Getting your session ready." />;
  }

  if (!auth.authenticated) {
    return <AuthGate title="Admin statistics" />;
  }

  if (!auth.admin) {
    return (
      <CenteredMessage
        title="Admin access required"
        body="Sign in with an account that has bbagent-admin-role."
      />
    );
  }

  const topModel = data?.models?.[0];
  const topTool = data?.tools?.[0];
  const topLimitUsage = limitData?.usages?.[0];

  return (
    <div className="account-shell admin-shell">
      <SiteNav auth={auth} />
      <main className="account-main admin-main">
        <section className="account-heading admin-heading">
          <p className="eyebrow">Admin</p>
          <h1>Agent statistics</h1>
          <p>
            {data?.period
              ? `${formatDateTime(data.period.from)} to ${formatDateTime(data.period.to)}`
              : "Recent message activity"}
          </p>
          <nav className="admin-page-nav" aria-label="Admin pages">
            <a className="active" href="/admin">
              Statistics
            </a>
            <a href="/admin/feedback">Feedback</a>
          </nav>
        </section>

        <section className="admin-toolbar">
          <div className="segmented-control" aria-label="Statistics period">
            <button onClick={() => applyPreset(24, setFromInput, setToInput)}>24h</button>
            <button onClick={() => applyPreset(24 * 7, setFromInput, setToInput)}>7d</button>
            <button onClick={() => applyPreset(24 * 30, setFromInput, setToInput)}>30d</button>
          </div>
          <label>
            <span>From</span>
            <input
              type="datetime-local"
              value={fromInput}
              onChange={(event) => setFromInput(event.target.value)}
            />
          </label>
          <label>
            <span>To</span>
            <input
              type="datetime-local"
              value={toInput}
              onChange={(event) => setToInput(event.target.value)}
            />
          </label>
          <button className="button button-primary" disabled={loading} onClick={() => void load()}>
            {loading ? "Refreshing" : "Refresh"}
          </button>
        </section>

        {error ? <p className="error-banner">{error}</p> : null}

        <section className="admin-stat-grid">
          <MetricTile label="Messages" value={formatCount(data?.total_messages)} />
          <MetricTile label="Active users" value={formatCount(data?.active_users)} />
          <MetricTile
            label="Messages per user"
            value={(data?.average_messages_per_user || 0).toFixed(1)}
          />
          <MetricTile label="Tool calls" value={formatCount(data?.total_tool_calls)} />
          <MetricTile label="Tool success" value={formatPercent(data?.tool_success_rate)} />
          <MetricTile label="Top model" value={topModel?.model_label || "None"} />
          <MetricTile label="Top tool" value={formatToolLabel(topTool?.tool_name)} />
          <MetricTile
            label="Highest quota use"
            value={formatPercent(topLimitUsage?.percentage)}
          />
        </section>

        <section className="admin-content-grid">
          <article className="admin-panel">
            <header>
              <p className="eyebrow">Models</p>
              <h2>Served by model</h2>
            </header>
            <div className="model-usage-list">
              {(data?.models || []).length === 0 ? (
                <p className="muted">No messages in this period.</p>
              ) : (
                data?.models?.map((model) => (
                  <div className="model-usage-row" key={`${model.model_key}-${String(model.is_premium)}`}>
                    <div>
                      <strong>{model.model_label}</strong>
                      <span>
                        {model.responses_model} / {model.is_premium ? "Paid" : "Free"}
                      </span>
                    </div>
                    <div className="model-usage-meter" aria-hidden="true">
                      <span style={{ width: `${Math.max(model.percentage || 0, 0.02) * 100}%` }} />
                    </div>
                    <p>
                      {formatCount(model.message_count)} messages /{" "}
                      {formatCount(model.active_users)} users / {formatPercent(model.percentage)}
                    </p>
                  </div>
                ))
              )}
            </div>
          </article>

          <article className="admin-panel">
            <header>
              <p className="eyebrow">Senders</p>
              <h2>Top accounts</h2>
            </header>
            <SenderUsage senders={data?.senders || []} />
          </article>

          <article className="admin-panel">
            <header>
              <p className="eyebrow">Rate limits</p>
              <h2>Daily response usage</h2>
            </header>
            <RateLimitUsage usages={limitData?.usages || []} />
          </article>

          <article className="admin-panel">
            <header>
              <p className="eyebrow">Tools</p>
              <h2>Usage by tool</h2>
            </header>
            <ToolUsage tools={data?.tools || []} />
          </article>

          <article className="admin-panel">
            <header>
              <p className="eyebrow">Account types</p>
              <h2>Tool success mix</h2>
            </header>
            <ToolAccountTypes accountTypes={data?.tool_account_types || []} />
          </article>

          <article className="admin-panel">
            <header>
              <p className="eyebrow">Timeline</p>
              <h2>Message volume</h2>
            </header>
            <Timeline buckets={data?.timeline || []} />
          </article>
        </section>
      </main>
    </div>
  );
}

function ToolUsage({ tools }: { tools: AdminToolStats[] }) {
  const max = Math.max(1, ...tools.map((tool) => tool.call_count || 0));
  if (tools.length === 0) {
    return <p className="muted">No tool calls in this period.</p>;
  }
  return (
    <div className="tool-usage-list">
      {tools.map((tool) => {
        const count = tool.call_count || 0;
        return (
          <div className="tool-usage-row" key={`${tool.tool_category}-${tool.tool_name}`}>
            <div>
              <strong>{formatToolLabel(tool.tool_name)}</strong>
              <span>
                {formatCategory(tool.tool_category)} / last used {formatDateTime(tool.last_used_at)}
              </span>
            </div>
            <div className="tool-usage-meter" aria-hidden="true">
              <span style={{ width: `${(count / max) * 100}%` }} />
            </div>
            <p>
              {formatCount(count)} calls / {formatCount(tool.successful_calls)} ok /{" "}
              {formatCount(tool.failed_calls)} failed / {formatPercent(tool.success_rate)} success
              / avg {formatDurationMs(tool.average_duration_ms)}
            </p>
          </div>
        );
      })}
    </div>
  );
}

function ToolAccountTypes({
  accountTypes,
}: {
  accountTypes: AdminToolAccountTypeStats[];
}) {
  const max = Math.max(1, ...accountTypes.map((accountType) => accountType.call_count || 0));
  if (accountTypes.length === 0) {
    return <p className="muted">No tool calls by account type in this period.</p>;
  }
  return (
    <div className="tool-account-type-list">
      {accountTypes.map((accountType) => {
        const count = accountType.call_count || 0;
        return (
          <div
            className="tool-account-type-row"
            key={String(accountType.is_premium)}
          >
            <div>
              <strong>{accountType.is_premium ? "Paid" : "Free"}</strong>
              <span>{accountType.is_premium ? "Premium accounts" : "Free accounts"}</span>
            </div>
            <div className="tool-account-type-meter" aria-hidden="true">
              <span style={{ width: `${(count / max) * 100}%` }} />
            </div>
            <p>
              {formatCount(count)} calls / {formatCount(accountType.active_users)} users /{" "}
              {formatPercent(accountType.success_rate)} success /{" "}
              {formatPercent(accountType.percentage)} of tool volume
            </p>
          </div>
        );
      })}
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

function SenderUsage({ senders }: { senders: AdminSenderStats[] }) {
  const max = Math.max(1, ...senders.map((sender) => sender.message_count || 0));
  if (senders.length === 0) {
    return <p className="muted">No sender buckets in this period.</p>;
  }
  return (
    <div className="sender-usage-list">
      {senders.map((sender) => {
        const count = sender.message_count || 0;
        return (
          <div className="sender-usage-row" key={sender.account_key_hash}>
            <div>
              <strong>#{sender.account_bucket || "unknown"}</strong>
              <span title={sender.account_key_hash}>
                Last seen {formatDateTime(sender.last_seen_at)}
              </span>
            </div>
            <div className="sender-usage-meter" aria-hidden="true">
              <span style={{ width: `${(count / max) * 100}%` }} />
            </div>
            <p>
              {formatCount(count)} messages / {formatPercent(sender.percentage)} of volume /{" "}
              {senderModelSummary(sender)}
            </p>
          </div>
        );
      })}
    </div>
  );
}

function RateLimitUsage({ usages }: { usages: AdminRateLimitUsage[] }) {
  if (usages.length === 0) {
    return <p className="muted">No rate-limit usage in the current daily window.</p>;
  }
  return (
    <div className="rate-limit-usage-list">
      {usages.map((usage) => (
        <div className="rate-limit-usage-row" key={`${usage.limit_key}-${usage.scope_key}`}>
          <div>
            <strong>#{usage.account_bucket || "unknown"}</strong>
            <span>
              {usage.is_premium ? "Paid" : "Free"} / resets {formatDateTime(usage.window_end)}
            </span>
          </div>
          <div className="rate-limit-usage-meter" aria-hidden="true">
            <span style={{ width: `${Math.max(usage.percentage || 0, 0.02) * 100}%` }} />
          </div>
          <p>
            {formatCount(usage.used)} of {formatCount(usage.limit)} used /{" "}
            {formatCount(usage.remaining)} remaining
          </p>
        </div>
      ))}
    </div>
  );
}

function Timeline({ buckets }: { buckets: AdminStatsBucket[] }) {
  const max = Math.max(1, ...buckets.map((bucket) => bucket.message_count || 0));
  if (buckets.length === 0) {
    return <p className="muted">No timeline buckets available.</p>;
  }
  return (
    <div className="timeline-list">
      {buckets.map((bucket) => {
        const count = bucket.message_count || 0;
        return (
          <div className="timeline-row" key={`${bucket.bucket_start}-${bucket.bucket_end}`}>
            <time>{formatBucket(bucket.bucket_start)}</time>
            <div className="timeline-bar" aria-hidden="true">
              <span style={{ width: `${(count / max) * 100}%` }} />
            </div>
            <strong>{formatCount(count)}</strong>
            <small>{bucketModelSummary(bucket)}</small>
          </div>
        );
      })}
    </div>
  );
}

function applyPreset(
  hours: number,
  setFromInput: (value: string) => void,
  setToInput: (value: string) => void,
) {
  const to = new Date();
  const from = new Date(to.getTime() - hours * 60 * 60 * 1000);
  setFromInput(toLocalInputValue(from));
  setToInput(toLocalInputValue(to));
}

function formatToolLabel(value: string | undefined): string {
  return formatTitleLabel(value, "None");
}

function formatCategory(value: string | undefined): string {
  return formatToolLabel(value || "other");
}

function bucketModelSummary(bucket: AdminStatsBucket): string {
  const models = bucket.models || [];
  if (models.length === 0) {
    return "No messages";
  }
  return models
    .map((model) => `${model.model_label}: ${formatCount(model.message_count)}`)
    .join(" / ");
}

function senderModelSummary(sender: AdminSenderStats): string {
  const models = sender.models || [];
  if (models.length === 0) {
    return "No model mix";
  }
  return models
    .slice(0, 2)
    .map((model) => `${model.model_label}: ${formatCount(model.message_count)}`)
    .join(" / ");
}
