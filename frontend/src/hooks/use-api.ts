import React from "react";

import { websiteAccountApi } from "../services/api-client";

export function useLinkedAccounts() {
  const [data, setData] =
    React.useState<Awaited<ReturnType<typeof websiteAccountApi.listLinkedAccounts>> | null>(null);
  const [isLoading, setIsLoading] = React.useState(false);
  const [error, setError] = React.useState<Error | null>(null);

  const fetchData = React.useCallback(async () => {
    setIsLoading(true);
    try {
      setData(await websiteAccountApi.listLinkedAccounts());
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err : new Error("Failed to load account links"));
    } finally {
      setIsLoading(false);
    }
  }, []);

  return { data, isLoading, error, fetchData };
}
