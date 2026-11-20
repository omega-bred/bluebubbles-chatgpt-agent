import axios, { type AxiosResponse } from "axios";

import {
  AdminApi,
  AppClipApi,
  Configuration,
  ContactApi,
  ConversationSettingsApi,
  SubscriptionApi,
  TextingApi,
  WebsiteAccountApi,
  type AdminAccountBlockRequest,
  type AdminAccountBlockTargetType,
  type AdminPremiumGrantTargetType,
  type AppClipEventRequest,
  type ConversationSettingsUpdateRequest,
  type ConversationGroupMemoryUpdateRequest,
  type ConversationCatchupPreferencesUpdateRequest,
  type ContactMessageRequest,
  type WebsiteAccountDeleteLinkedAccountTypeEnum,
  type WebsiteModelSelectionRequest,
} from "../client";
import type { WebsiteAccountRedeemLinkRequest } from "../client";
import { getAccessToken, login } from "../auth/keycloak";
import { trackEvent } from "./analytics";

const axiosInstance = axios.create();
const apiBasePath = import.meta.env.VITE_API_URL || "";
const appClipSessionHeader = "X-App-Clip-Session";
const appClipResponseHeader = appClipSessionHeader.toLowerCase();
const appClipSessionTokenKey = "bluechat.conversationSettings.sessionToken";
const freshSettingsLink = "Open a fresh BlueChatAI conversation settings link from Messages.";
let appClipRequests: Promise<unknown> = Promise.resolve();

function enqueueAppClipRequest<T>(request: () => Promise<T>): Promise<T> {
  const result = appClipRequests.then(request);
  appClipRequests = result.catch(() => undefined);
  return result;
}

function useAppClipSession<T>(
  request: (token: string) => Promise<AxiosResponse<T>>,
): Promise<T> {
  return enqueueAppClipRequest(async () => {
    const token = window.sessionStorage.getItem(appClipSessionTokenKey);
    if (!token) {
      throw new Error(freshSettingsLink);
    }
    let response: AxiosResponse<T> | undefined;
    try {
      response = await request(token);
      return response.data;
    } catch (error) {
      if (axios.isAxiosError<T>(error)) {
        response = error.response;
        if (response?.status === 401 && !response.headers[appClipResponseHeader]) {
          window.sessionStorage.removeItem(appClipSessionTokenKey);
          throw new Error(freshSettingsLink);
        }
      }
      throw error;
    } finally {
      const replacement = response?.headers[appClipResponseHeader];
      if (typeof replacement === "string" && replacement) {
        window.sessionStorage.setItem(appClipSessionTokenKey, replacement);
      }
    }
  });
}

axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status || 0;
    trackEvent("web_api_request_failed", {
      status_code: status,
      path: apiPath(error?.config?.url),
    });
    if (status === 401 && !hasAppClipSessionHeader(error?.config?.headers)) {
      void login();
    }
    return Promise.reject(error);
  },
);

async function websiteAccountClient(): Promise<WebsiteAccountApi> {
  const config = await authenticatedConfiguration();
  return new WebsiteAccountApi(config, config.basePath, axiosInstance);
}

async function adminClient(): Promise<AdminApi> {
  const config = await authenticatedConfiguration();
  return new AdminApi(config, config.basePath, axiosInstance);
}

async function subscriptionClient(): Promise<SubscriptionApi> {
  const config = await authenticatedConfiguration();
  return new SubscriptionApi(config, config.basePath, axiosInstance);
}

function appClipClient(sessionToken?: string): AppClipApi {
  const config = sessionToken ? appClipSessionConfiguration(sessionToken) : publicConfiguration();
  return new AppClipApi(config, config.basePath, axiosInstance);
}

function conversationSettingsClient(sessionToken: string): ConversationSettingsApi {
  const config = appClipSessionConfiguration(sessionToken);
  return new ConversationSettingsApi(config, config.basePath, axiosInstance);
}

function contactClient(): ContactApi {
  const config = publicConfiguration();
  return new ContactApi(config, config.basePath, axiosInstance);
}

function textingClient(): TextingApi {
  const config = publicConfiguration();
  return new TextingApi(config, config.basePath, axiosInstance);
}

async function authenticatedContactClient(): Promise<ContactApi> {
  const config = await authenticatedConfiguration();
  return new ContactApi(config, config.basePath, axiosInstance);
}

async function authenticatedConfiguration(): Promise<Configuration> {
  const token = await getAccessToken();
  return new Configuration({
    basePath: apiBasePath,
    baseOptions: {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    },
  });
}

function publicConfiguration(): Configuration {
  return new Configuration({
    basePath: apiBasePath,
  });
}

function appClipSessionConfiguration(sessionToken: string): Configuration {
  return new Configuration({
    basePath: apiBasePath,
    baseOptions: {
      headers: {
        [appClipSessionHeader]: sessionToken,
      },
    },
  });
}

export const websiteAccountApi = {
  get: async () => {
    const client = await websiteAccountClient();
    return (await client.websiteAccountGet()).data;
  },

  listLinkedAccounts: async () => {
    const client = await websiteAccountClient();
    return (await client.websiteAccountListLinkedAccounts()).data;
  },

  redeemLink: async (token: string) => {
    const client = await websiteAccountClient();
    const request: WebsiteAccountRedeemLinkRequest = { token };
    return (await client.websiteAccountRedeemLink(request)).data;
  },

  deleteLinkedAccount: async (
    type: WebsiteAccountDeleteLinkedAccountTypeEnum,
    accountKey: string,
  ) => {
    const client = await websiteAccountClient();
    return (await client.websiteAccountDeleteLinkedAccount(type, accountKey)).data;
  },

  updateModel: async (model: string) => {
    const client = await websiteAccountClient();
    const request: WebsiteModelSelectionRequest = {
      model: model as WebsiteModelSelectionRequest["model"],
    };
    return (await client.websiteAccountUpdateModel(request)).data;
  },
};

export const appClipApi = {
  createSession: (linkToken: string) =>
    enqueueAppClipRequest(async () => {
      const response = await appClipClient().appClipCreateSession({ link_token: linkToken });
      if (response.data.purpose !== "conversation_settings") {
        throw new Error("This link opens account settings, not conversation settings.");
      }
      window.sessionStorage.setItem(appClipSessionTokenKey, response.data.session_token);
      return response.data;
    }),

  getSession: () => useAppClipSession((token) => appClipClient(token).appClipGetSession()),

  trackEvent: (request: AppClipEventRequest) =>
    useAppClipSession((token) => appClipClient(token).appClipCreateEvent(request))
      .catch(() => undefined),
};

export const conversationSettingsApi = {
  get: () => useAppClipSession((token) => conversationSettingsClient(token).conversationSettingsGet()),

  updateResponsiveness: (responsiveness: ConversationSettingsUpdateRequest["responsiveness"]) =>
    useAppClipSession((token) =>
      conversationSettingsClient(token).conversationSettingsUpdateResponsiveness({ responsiveness }),
    ),

  updateGroupMemory: (enabled: ConversationGroupMemoryUpdateRequest["enabled"]) =>
    useAppClipSession((token) =>
      conversationSettingsClient(token).conversationSettingsUpdateGroupMemory({ enabled }),
    ),

  updateCatchups: (request: ConversationCatchupPreferencesUpdateRequest) =>
    useAppClipSession((token) => conversationSettingsClient(token).conversationSettingsUpdateCatchups(request)),
};

export const contactApi = {
  getConfig: async () => {
    const client = contactClient();
    return (await client.contactGetConfig()).data;
  },

  createMessage: async (request: ContactMessageRequest, authenticated = false) => {
    const client = authenticated ? await authenticatedContactClient() : contactClient();
    return (await client.contactCreateMessage(request)).data;
  },
};

export const textingApi = {
  getNumber: async () => {
    const client = textingClient();
    return (await client.textingGetNumber()).data;
  },
};

export const subscriptionApi = {
  get: async () => {
    const client = await subscriptionClient();
    return (await client.subscriptionGet()).data;
  },

  createCheckout: async (planKey?: string, provider?: string) => {
    const client = await subscriptionClient();
    return (await client.subscriptionCreateCheckout({ plan_key: planKey, provider })).data;
  },

  createPortal: async () => {
    const client = await subscriptionClient();
    return (await client.subscriptionCreatePortal()).data;
  },
};

export const adminApi = {
  getStatistics: async (from: string, to: string) => {
    const client = await adminClient();
    return (await client.adminGetStatistics(from, to)).data;
  },

  getRateLimitUsage: async (limitKey?: string, limit?: number) => {
    const client = await adminClient();
    return (await client.adminGetRateLimitUsage(limitKey, limit)).data;
  },

  listFeedback: async (status: AdminFeedbackFilter = "unread", limit = 100) => {
    const client = await adminClient();
    const feedbackStatus = status as Parameters<AdminApi["adminListFeedback"]>[0];
    return (await client.adminListFeedback(feedbackStatus, limit)).data;
  },

  markFeedbackRead: async (feedbackId: string) => {
    const client = await adminClient();
    return (await client.adminMarkFeedbackRead({ feedback_id: feedbackId })).data;
  },

  markFeedbackUnread: async (feedbackId: string) => {
    const client = await adminClient();
    return (await client.adminMarkFeedbackUnread({ feedback_id: feedbackId })).data;
  },

  listSubscriptions: async (limit = 100) => {
    const client = await adminClient();
    return (await client.adminListSubscriptions(limit)).data;
  },

  syncSubscription: async (subscriptionId: string) => {
    const client = await adminClient();
    return (await client.adminSyncSubscription({ subscription_id: subscriptionId })).data;
  },

  suspendSubscription: async (subscriptionId: string, reason?: string) => {
    const client = await adminClient();
    return (await client.adminSuspendSubscription({ subscription_id: subscriptionId, reason })).data;
  },

  unsuspendSubscription: async (subscriptionId: string) => {
    const client = await adminClient();
    return (await client.adminUnsuspendSubscription({ subscription_id: subscriptionId })).data;
  },

  grantPremium: async (target: string, targetType?: AdminPremiumGrantTargetType) => {
    const client = await adminClient();
    return (await client.adminGrantPremium({ target, target_type: targetType })).data;
  },

  revokePremium: async (target: string, targetType?: AdminPremiumGrantTargetType) => {
    const client = await adminClient();
    return (await client.adminRevokePremium({ target, target_type: targetType })).data;
  },

  listAccountBlocks: async (limit = 100) => {
    const client = await adminClient();
    return (await client.adminListAccountBlocks(limit)).data;
  },

  blockAccount: async (
    target: string,
    targetType: AdminAccountBlockTargetType,
    reason?: string,
  ) => {
    const client = await adminClient();
    const request: AdminAccountBlockRequest = {
      target,
      target_type: targetType,
      reason,
    };
    return (await client.adminBlockAccount(request)).data;
  },

  unblockAccount: async (target: string, targetType: AdminAccountBlockTargetType) => {
    const client = await adminClient();
    return (await client.adminUnblockAccount({ target, target_type: targetType })).data;
  },
};

export type AdminFeedbackFilter = "all" | "unread" | "read";

function apiPath(url: string | undefined): string {
  if (!url) {
    return "unknown";
  }
  try {
    return new URL(url, window.location.origin).pathname;
  } catch {
    return "unknown";
  }
}

function hasAppClipSessionHeader(headers: unknown): boolean {
  if (!headers || typeof headers !== "object") {
    return false;
  }
  const record = headers as Record<string, unknown>;
  return Boolean(record[appClipSessionHeader] || record[appClipSessionHeader.toLowerCase()]);
}
