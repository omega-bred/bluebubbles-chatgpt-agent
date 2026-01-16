import React from "react";
import { useAuth } from "@clerk/clerk-react";
// import { groupsApi, framesApi } from "../services/api-client";
import { Group, Frame } from "../types/models";

export function useGroups() {
  const { isLoaded, isSignedIn } = useAuth();
  const [data, setData] = React.useState<Group[] | null>(null);
  const [isLoading, setIsLoading] = React.useState(true);
  const [error, setError] = React.useState<Error | null>(null);

  const fetchData = React.useCallback(async () => {
    setIsLoading(true);
    try {
      const result = []
      setData(result);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err : new Error("Failed to fetch groups"));
    } finally {
      setIsLoading(false);
    }
  }, []);

  React.useEffect(() => {
    if (isLoaded && isSignedIn) {
      fetchData();
    }
  }, [fetchData, isLoaded, isSignedIn]);

  return { data, isLoading, error, setData, fetchData };
}

export function useFrames() {
  const { isLoaded, isSignedIn } = useAuth();
  const [data, setData] = React.useState<Frame[] | null>(null);
  const [isLoading, setIsLoading] = React.useState(true);
  const [error, setError] = React.useState<Error | null>(null);

  const fetchData = React.useCallback(async () => {
    setIsLoading(true);
    try {
      const result = [];
      setData(result);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err : new Error("Failed to fetch frames"));
    } finally {
      setIsLoading(false);
    }
  }, []);

  React.useEffect(() => {
    if (isLoaded && isSignedIn) {
      fetchData();
    }
  }, [fetchData, isLoaded, isSignedIn]);

  return { data, isLoading, error, setData, fetchData };
}