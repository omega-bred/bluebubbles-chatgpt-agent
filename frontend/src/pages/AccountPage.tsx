import React from "react";

import type { AuthState } from "../auth/useKeycloak";
import type {
  WebsiteAccountIdentity,
  AdminSubscriptionItem,
  SubscriptionPlan,
  SubscriptionSummaryResponse,
  WebsiteIntegrationSummary,
  WebsiteLinkedAccountsResponse,
  WebsiteLinkedIntegrationAccount,
  WebsiteModelOption,
  WebsiteUsageLimitSummary,
} from "../client";
import { AuthGate } from "../components/AuthGate";
import { CenteredMessage } from "../components/CenteredMessage";
import { SiteNav } from "../components/SiteNav";
import { subscriptionApi, websiteAccountApi } from "../services/api-client";
import { trackEvent } from "../services/analytics";
import { displayModelLabel } from "../utils/model-label";

export function AccountPage({ auth }: { auth: AuthState }) {
  const [data, setData] = React.useState<WebsiteLinkedAccountsResponse | null>(null);
  const [subscription, setSubscription] = React.useState<SubscriptionSummaryResponse | null>(null);
  const [error, setError] = React.useState<string | null>(null);
  const [loading, setLoading] = React.useState(false);
  const [billingBusy, setBillingBusy] = React.useState(false);
  const hasLoaded = data !== null || error !== null;

  const load = React.useCallback(async () => {
    if (!auth.authenticated) {
      return;
    }
    setLoading(true);
    try {
      const [linkedAccounts, billing] = await Promise.all([
        websiteAccountApi.listLinkedAccounts(),
        subscriptionApi.get(),
      ]);
      setData(linkedAccounts);
      setSubscription(billing);
      trackEvent("web_account_loaded", {
        integration_count: linkedAccounts.integrations?.length || 0,
        is_premium: Boolean(billing.is_premium),
        subscription_count: billing.subscriptions?.length || 0,
      });
      setError(null);
    } catch (err) {
      trackEvent("web_account_load_failed");
      setError(err instanceof Error ? err.message : "Unable to load linked accounts.");
    } finally {
      setLoading(false);
    }
  }, [auth.authenticated]);

  React.useEffect(() => {
    void load();
  }, [load]);

  const createCheckout = React.useCallback(async (plan?: SubscriptionPlan) => {
    if (!plan) {
      setError("Premium billing is not configured yet.");
      return;
    }
    setBillingBusy(true);
    trackEvent("web_checkout_start", {
      plan_key: plan.key || "unknown",
      provider: plan.provider || "unknown",
    });
    try {
      const checkout = await subscriptionApi.createCheckout(plan.key, plan.provider);
      trackEvent("web_checkout_created", {
        provider: checkout.provider || "unknown",
        plan_key: checkout.plan_key || plan.key || "unknown",
      });
      window.location.assign(checkout.checkout_url);
    } catch (err) {
      trackEvent("web_checkout_failed", {
        plan_key: plan.key || "unknown",
        provider: plan.provider || "unknown",
      });
      setError(err instanceof Error ? err.message : "Unable to create checkout.");
    } finally {
      setBillingBusy(false);
    }
  }, []);

  const openPortal = React.useCallback(async () => {
    setBillingBusy(true);
    trackEvent("web_billing_portal_start");
    try {
      const portal = await subscriptionApi.createPortal();
      trackEvent("web_billing_portal_created", { provider: portal.provider || "unknown" });
      window.location.assign(portal.portal_url);
    } catch (err) {
      trackEvent("web_billing_portal_failed");
      setError(err instanceof Error ? err.message : "Unable to open billing portal.");
    } finally {
      setBillingBusy(false);
    }
  }, []);

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
            {data?.account?.display_name || data?.account?.email || "Your account"}
          </h1>
          <p>{data?.account?.email || "Signed in with Keycloak."}</p>
        </section>

        {error ? <p className="error-banner">{error}</p> : null}
        {loading && hasLoaded ? <p className="muted">Refreshing linked accounts.</p> : null}

        <BillingPanel
          subscription={subscription}
          busy={billingBusy}
          onUpgrade={createCheckout}
          onManage={openPortal}
        />

        <UsageLimitsPanel limits={data?.usage_limits || []} />

        <section className="linked-list" aria-busy={!hasLoaded || loading}>
          {!hasLoaded ? (
            <AccountLoader />
          ) : (data?.integrations || []).length === 0 ? (
            <EmptyLinks />
          ) : (
            data?.integrations?.map((integration) => (
              <LinkedIdentity
                key={integration.link?.link_id}
                integration={integration}
                onChanged={load}
              />
            ))
          )}
        </section>
      </main>
    </div>
  );
}

function BillingPanel({
  subscription,
  busy,
  onUpgrade,
  onManage,
}: {
  subscription: SubscriptionSummaryResponse | null;
  busy: boolean;
  onUpgrade: (plan?: SubscriptionPlan) => Promise<void>;
  onManage: () => Promise<void>;
}) {
  const activeSubscription = subscription?.subscriptions?.[0];
  const plans = subscription?.plans || [];
  const plan = plans[0];
  const isPremium = Boolean(subscription?.is_premium);
  const showCheckoutOptions = !isPremium && !activeSubscription;
  const planCheckoutCopy = checkoutOptionCopy(plan?.provider);
  return (
    <article className="billing-panel">
      <div>
        <p className="eyebrow">Billing</p>
        <h2>{isPremium ? "Premium access" : "Free access"}</h2>
        <p className="muted">
          {isPremium
            ? premiumAccessText(subscription, activeSubscription)
            : premiumPlanSummary(plans)}
        </p>
      </div>
      <div className="billing-actions">
        {activeSubscription ? (
          <button className="button button-secondary" disabled={busy} onClick={() => void onManage()}>
            {busy ? "Opening" : "Manage billing"}
          </button>
        ) : null}
        {showCheckoutOptions && plans.length <= 1 ? (
          <button className="button button-primary" disabled={busy || !plan} onClick={() => void onUpgrade(plan)}>
            {busy ? "Opening checkout" : planCheckoutCopy.action}
          </button>
        ) : null}
      </div>
      {showCheckoutOptions && plans.length > 1 ? (
        <div className="provider-plan-list">
          {plans.map((availablePlan, index) => {
            const checkoutCopy = checkoutOptionCopy(availablePlan.provider);
            return (
              <button
                className={`provider-plan-row ${index === 0 ? "primary" : ""}`}
                disabled={busy}
                key={`${availablePlan.provider}:${availablePlan.key}`}
                onClick={() => void onUpgrade(availablePlan)}
              >
                <span className="provider-plan-copy">
                  <span className="provider-plan-title">{checkoutCopy.title}</span>
                  <span className="provider-plan-note">
                    {providerPlanDescription(checkoutCopy.description, availablePlan)}
                  </span>
                  <span className="provider-plan-price">
                    {formatProviderLabel(availablePlan.provider)} / {formatPlanPrice(availablePlan)}
                  </span>
                </span>
                <span className="provider-plan-action">
                  {busy ? "Opening checkout" : checkoutCopy.action}
                </span>
              </button>
            );
          })}
        </div>
      ) : null}
      {activeSubscription ? (
        <SubscriptionRows plans={plans} subscriptions={subscription?.subscriptions || []} />
      ) : null}
    </article>
  );
}

function UsageLimitsPanel({ limits }: { limits: WebsiteUsageLimitSummary[] }) {
  const primaryLimit = limits[0];
  if (!primaryLimit) {
    return (
      <article className="usage-panel">
        <div>
          <p className="eyebrow">Usage</p>
          <h2>Usage limits</h2>
          <p className="muted">Your monthly usage will appear here once your account is active.</p>
        </div>
      </article>
    );
  }
  const percentage = clampPercentage(primaryLimit.percentage || 0);
  const remaining = Number(primaryLimit.remaining || 0);
  const limit = Number(primaryLimit.limit || 0);
  const used = Number(primaryLimit.used || 0);
  return (
    <article className="usage-panel">
      <div className="usage-panel-copy">
        <p className="eyebrow">Usage</p>
        <h2>{primaryLimit.limit_label || "Monthly assistant responses"}</h2>
        <p className="muted">
          {formatUsageCount(remaining)} responses remain before the monthly reset.
        </p>
      </div>
      <div
        className="usage-orbit"
        style={{ "--usage-fill": `${percentage * 360}deg` } as React.CSSProperties}
        aria-label={`${Math.round(percentage * 100)}% used`}
      >
        <span>{Math.round(percentage * 100)}%</span>
        <small>used</small>
      </div>
      <div className="usage-meter-block">
        <div className="usage-meter" aria-hidden="true">
          <span style={{ width: `${(percentage <= 0 ? 0 : Math.max(percentage, 0.015)) * 100}%` }} />
        </div>
        <div className="usage-stats">
          <span>
            <strong>{formatUsageCount(used)}</strong> used
          </span>
          <span>
            <strong>{formatUsageCount(limit)}</strong> monthly limit
          </span>
          <span>Resets {formatAccountDate(primaryLimit.window_end)}</span>
        </div>
      </div>
    </article>
  );
}

function providerPlanDescription(baseDescription: string, plan: SubscriptionPlan) {
  const planDescription = plan.description?.trim();
  const trialOffer = formatTrialOffer(plan.trial_duration_days);
  const parts = [baseDescription];
  if (trialOffer) {
    parts.push(`Includes ${trialOffer}.`);
  }
  if (planDescription) {
    parts.push(/[.!?]$/.test(planDescription) ? planDescription : `${planDescription}.`);
  }
  return parts.join(" ");
}

function SubscriptionRows({
  plans,
  subscriptions,
}: {
  plans: SubscriptionPlan[];
  subscriptions: AdminSubscriptionItem[];
}) {
  return (
    <div className="subscription-row-list">
      {subscriptions.slice(0, 3).map((subscription) => (
        <div className="subscription-row" key={subscription.subscription_id}>
          <div>
            <strong>{formatSubscriptionStatus(subscription.status)}</strong>
            <span>
              {formatProviderLabel(subscription.provider)} /{" "}
              {formatPlanLabel(subscription.plan_key, plans)}
            </span>
          </div>
          <p>{subscription.current_period_end ? `Renews ${formatAccountDate(subscription.current_period_end)}` : "No renewal date"}</p>
        </div>
      ))}
    </div>
  );
}

function premiumAccessText(
  subscription: SubscriptionSummaryResponse | null,
  activeSubscription: AdminSubscriptionItem | undefined,
) {
  if (subscription?.premium_until) {
    return `Premium through ${formatAccountDate(subscription.premium_until)}.`;
  }
  if (activeSubscription?.status) {
    return `Premium subscription is ${formatSubscriptionStatus(activeSubscription.status).toLowerCase()}.`;
  }
  return "Premium is active.";
}

function formatSubscriptionStatus(value: string | undefined) {
  if (!value) {
    return "Unknown";
  }
  return value
    .split(/[_-]+/)
    .filter(Boolean)
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join(" ");
}

function formatProviderLabel(value: string | undefined) {
  switch ((value || "").toLowerCase()) {
    case "btcpay":
      return "BTCPay";
    case "stripe":
      return "Stripe";
    default:
      return formatSubscriptionStatus(value);
  }
}

function checkoutOptionCopy(provider: string | undefined) {
  switch ((provider || "").toLowerCase()) {
    case "btcpay":
      return {
        title: "Bitcoin checkout",
        description: "Pay directly with Bitcoin through BTCPay.",
        action: "Pay with Bitcoin",
      };
    case "stripe":
      return {
        title: "Card checkout",
        description: "Fast checkout with cards, Apple Pay, and wallets.",
        action: "Pay with Card",
      };
    default:
      return {
        title: "Premium checkout",
        description: "Start premium access with the selected provider.",
        action: "Subscribe to Premium",
      };
  }
}

function premiumPlanSummary(plans: SubscriptionPlan[]) {
  if (plans.length === 0) {
    return "Premium billing is not configured yet.";
  }
  const bitcoinPlan = plans.find(
    (availablePlan) => availablePlan.provider?.toLowerCase() === "btcpay",
  );
  const cardPlan = plans.find(
    (availablePlan) => availablePlan.provider?.toLowerCase() === "stripe",
  );
  if (bitcoinPlan && cardPlan) {
    const trialIntro = premiumTrialIntro(plans);
    return `${trialIntro}${formatPlanPrice(bitcoinPlan)} with Bitcoin or ${formatPlanPrice(
      cardPlan,
    )} with card.`;
  }
  const plan = plans[0];
  const trialOffer = formatTrialOffer(plan.trial_duration_days);
  if (trialOffer) {
    return `${plan.display_name} starts with ${trialOffer}, then ${formatPlanPrice(plan)}.`;
  }
  return `${plan.display_name} is ${formatPlanPrice(plan)}.`;
}

function premiumTrialIntro(plans: SubscriptionPlan[]) {
  const trialOffer = plans.map((plan) => formatTrialOffer(plan.trial_duration_days)).find(Boolean);
  return trialOffer ? `Premium starts with ${trialOffer}, then ` : "Premium is ";
}

function formatPlanLabel(value: string | undefined, plans: SubscriptionPlan[]) {
  const configuredName = plans.find((plan) => plan.key === value)?.display_name;
  if (configuredName) {
    return configuredName;
  }
  return formatSubscriptionStatus(value);
}

function formatPlanPrice(plan: SubscriptionPlan) {
  const price = [plan.price_amount, plan.currency].filter(Boolean).join(" ");
  const interval = formatBillingInterval(plan.billing_interval);
  return [price, interval].filter(Boolean).join(" ");
}

function formatTrialOffer(days: number | undefined) {
  const trialDays = Number(days || 0);
  if (!Number.isFinite(trialDays) || trialDays <= 0) {
    return "";
  }
  if (trialDays >= 28 && trialDays <= 31) {
    return "a 1 month free trial";
  }
  if (trialDays === 1) {
    return "a 1 day free trial";
  }
  return `a ${trialDays}-day free trial`;
}

function formatBillingInterval(value: string | undefined) {
  switch ((value || "").toLowerCase()) {
    case "month":
    case "monthly":
      return "/ month";
    case "year":
    case "yearly":
    case "annual":
    case "annually":
      return "/ year";
    default:
      return value ? `/${value}` : "";
  }
}

function formatAccountDate(value: Date | number | string | null | undefined) {
  if (!value) {
    return "";
  }
  const date = value instanceof Date ? value : new Date(value);
  return Number.isFinite(date.getTime())
    ? new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date)
    : "";
}

function formatUsageCount(value: number | string | undefined) {
  const numeric = Number(value || 0);
  return Number.isFinite(numeric) ? new Intl.NumberFormat().format(numeric) : "0";
}

function clampPercentage(value: number) {
  if (!Number.isFinite(value)) {
    return 0;
  }
  return Math.max(0, Math.min(1, value));
}

function AccountLoader() {
  return (
    <article className="account-loader" aria-label="Loading linked accounts">
      <div className="account-loader-top">
        <span className="account-loader-spinner" aria-hidden="true" />
        <div>
          <p className="eyebrow">Account links</p>
          <h2>Loading linked accounts</h2>
        </div>
      </div>
      <div className="account-loader-skeleton" aria-hidden="true">
        <span className="skeleton-line wide" />
        <span className="skeleton-line medium" />
        <span className="skeleton-pill-row">
          <span className="skeleton-pill" />
          <span className="skeleton-pill" />
          <span className="skeleton-pill short" />
        </span>
      </div>
    </article>
  );
}

function LinkedIdentity({
  integration,
  onChanged,
}: {
  integration: WebsiteIntegrationSummary;
  onChanged: () => Promise<void>;
}) {
  const [unlinkingAccountKey, setUnlinkingAccountKey] = React.useState<string | null>(null);
  const [modelBusy, setModelBusy] = React.useState(false);
  const [modelError, setModelError] = React.useState<string | null>(null);
  const link = integration.link;
  if (!link) {
    return null;
  }
  const calendars = integration.gcal_accounts || [];
  const identities = link.identities || [];
  const linkedAccounts = integration.linked_accounts || [];
  const modelAccess = integration.model_access;
  const modelLabel = modelAccess ? displayModelLabel(modelAccess.current_model_label) : "";
  const accountLabel = modelAccess?.is_premium ? "Premium" : "Free";
  const selectableModels =
    modelAccess?.available_models?.filter(
      (model) => model.enabled && model.model !== "local",
    ) || [];
  const accessNote =
    modelAccess && modelAccess.is_premium
      ? `${accountLabel} account · ${modelLabel}`
      : `${accountLabel} account`;
  const updateModel = async (model: string) => {
    setModelBusy(true);
    setModelError(null);
    trackEvent("web_model_selection_start", { model });
    try {
      await websiteAccountApi.updateModel(model);
      trackEvent("web_model_selection_updated", { model });
      await onChanged();
    } catch (err) {
      trackEvent("web_model_selection_failed", { model });
      setModelError(err instanceof Error ? err.message : "Unable to update model.");
    } finally {
      setModelBusy(false);
    }
  };
  return (
    <article className="linked-item">
      <div>
        <p className="eyebrow">Chat identities</p>
        <h2>Linked chat addresses</h2>
        <p className="muted">These addresses are treated as the same user across tools.</p>
        {modelAccess ? <p className="model-note">{accessNote}</p> : null}
      </div>
      <div className="integration-pills">
        {modelAccess ? (
          <span className={modelAccess.is_premium ? "pill premium" : "pill"}>
            {modelLabel === "Free" ? "Free" : `Model ${modelLabel}`}
          </span>
        ) : null}
        <span className={calendars.length > 0 ? "pill good" : "pill"}>
          Google Calendar{" "}
          {calendars.length > 0
            ? calendars.map((item) => item.account_id).join(", ")
            : "not linked"}
        </span>
      </div>
      {modelAccess?.model_selection_configurable && selectableModels.length > 0 ? (
        <ModelSelector
          currentModel={modelAccess.current_model}
          models={selectableModels}
          busy={modelBusy}
          onChange={updateModel}
        />
      ) : null}
      {modelError ? <p className="error-text">{modelError}</p> : null}
      {identities.length > 0 || linkedAccounts.length > 0 ? (
        <div className="linked-account-list">
          {identities.map((identity) => (
            <IdentityRow
              key={`${identity.type}:${identity.normalized_identifier}`}
              identity={identity}
            />
          ))}
          {linkedAccounts.map((account) => (
            <LinkedAccountRow
              key={`${account.type}:${account.account_key}`}
              account={account}
              unlinking={unlinkingAccountKey === account.account_key}
              onUnlink={async () => {
                setUnlinkingAccountKey(account.account_key);
                trackEvent("web_integration_unlink_start", { type: account.type });
                try {
                  await websiteAccountApi.deleteLinkedAccount(account.type, account.account_key);
                  trackEvent("web_integration_unlinked", { type: account.type });
                  await onChanged();
                } catch (err) {
                  trackEvent("web_integration_unlink_failed", { type: account.type });
                  throw err;
                } finally {
                  setUnlinkingAccountKey(null);
                }
              }}
            />
          ))}
        </div>
      ) : null}
    </article>
  );
}

function ModelSelector({
  currentModel,
  models,
  busy,
  onChange,
}: {
  currentModel?: string;
  models: WebsiteModelOption[];
  busy: boolean;
  onChange: (model: string) => Promise<void>;
}) {
  return (
    <label className="model-picker">
      <span>Assistant model</span>
      <select
        value={currentModel || ""}
        disabled={busy}
        onChange={(event) => void onChange(event.target.value)}
      >
        {models.map((model) => (
          <option key={model.model} value={model.model}>
            {displayModelLabel(model.label)}
          </option>
        ))}
      </select>
    </label>
  );
}

function IdentityRow({ identity }: { identity: WebsiteAccountIdentity }) {
  return (
    <div className="linked-account-row">
      <div>
        <p className="linked-account-type">{identityTypeLabel(identity.type)}</p>
        <p className="linked-account-email">{identity.identifier}</p>
      </div>
    </div>
  );
}

function LinkedAccountRow({
  account,
  unlinking,
  onUnlink,
}: {
  account: WebsiteLinkedIntegrationAccount;
  unlinking: boolean;
  onUnlink: () => Promise<void>;
}) {
  const typeLabel = "Google Calendar";
  const identifier = account.email || account.account_key;
  return (
    <div className="linked-account-row">
      <div>
        <p className="linked-account-type">{typeLabel}</p>
        <p className="linked-account-email">{identifier}</p>
      </div>
      <button
        className="button button-secondary compact"
        disabled={!account.unlinkable || unlinking}
        onClick={() => void onUnlink()}
      >
        {unlinking ? "Unlinking" : "Unlink"}
      </button>
    </div>
  );
}

function EmptyLinks() {
  return (
    <article className="empty-state">
      <h2>No BlueChat senders linked yet.</h2>
      <p>
        Text the bot and ask it to link your website account. It will send a short-lived login link
        back in BlueChat.
      </p>
    </article>
  );
}

function identityTypeLabel(type: WebsiteAccountIdentity["type"]) {
  switch (type) {
    case "imessage_email":
      return "BlueChat email";
    case "imessage_phone":
      return "BlueChat phone";
    case "lxmf_address":
      return "LXMF address";
    default:
      return "Chat address";
  }
}
