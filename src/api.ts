import { useCallback, useEffect, useState } from "react";

export type ApiToken = "dash-token" | "cs-token" | "oncall-token" | "admin-token" | "ingest-token";

interface Envelope<T> {
  code: number;
  message: string;
  request_id: string;
  server_time: string;
  data: T;
}

export class ApiError extends Error {
  constructor(message: string, readonly status: number, readonly code?: number) {
    super(message);
  }
}

export async function apiRequest<T>(path: string, token: ApiToken = "dash-token", init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });
  const payload = await response.json().catch(() => null) as Envelope<T> | null;
  if (!response.ok || !payload || payload.code !== 0) {
    throw new ApiError(payload?.message || `请求失败 (${response.status})`, response.status, payload?.code);
  }
  return payload.data;
}

export function useApi<T>(path: string | null, token: ApiToken = "dash-token") {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(Boolean(path));
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision(value => value + 1), []);

  useEffect(() => {
    if (!path) {
      setLoading(false);
      return;
    }
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    apiRequest<T>(path, token, { signal: controller.signal })
      .then(setData)
      .catch(reason => {
        if (reason?.name !== "AbortError") setError(reason instanceof Error ? reason.message : "接口请求失败");
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [path, token, revision]);

  return { data, error, loading, refresh };
}

export const query = (path: string, params: Record<string, string | number | undefined>) => {
  const search = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") search.set(key, String(value));
  });
  return `${path}?${search}`;
};

export const formatApiValue = (value: number, unit: string) => {
  if (unit === "ratio") return `${(value * 100).toFixed(value < 0.01 ? 2 : 1)}%`;
  if (unit === "fen") return value >= 1_000_000 ? `¥ ${(value / 1_000_000).toFixed(1)}万` : `¥ ${(value / 100).toLocaleString("zh-CN")}`;
  if (unit === "ms") return `${value.toLocaleString("zh-CN")}ms`;
  if (unit === "count") return value.toLocaleString("zh-CN");
  return String(value);
};
