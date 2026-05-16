import axios from "axios";

import {
  Configuration,
  WebsiteAccountApi,
  type WebsiteAccountDeleteLinkedAccountTypeEnum,
} from "../client";
import type { WebsiteAccountRedeemLinkRequest } from "../client";
import { getAccessToken, login } from "../auth/keycloak";

const axiosInstance = axios.create();

axiosInstance.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.response?.status === 401) {
      void login();
    }
    return Promise.reject(error);
  },
);

async function websiteAccountClient(): Promise<WebsiteAccountApi> {
  const token = await getAccessToken();
  const config = new Configuration({
    basePath: import.meta.env.VITE_API_URL || "",
    baseOptions: {
      headers: {
        Authorization: `Bearer ${token}`,
      },
    },
  });
  return new WebsiteAccountApi(config, config.basePath, axiosInstance);
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

  deleteLink: async (linkId: string) => {
    const client = await websiteAccountClient();
    return (await client.websiteAccountDeleteLink(linkId)).data;
  },

  deleteLinkedAccount: async (
    type: WebsiteAccountDeleteLinkedAccountTypeEnum,
    accountKey: string,
  ) => {
    const client = await websiteAccountClient();
    return (await client.websiteAccountDeleteLinkedAccount(type, accountKey)).data;
  },
};
