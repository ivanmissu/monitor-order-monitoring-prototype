// ── 数据获取 Hooks ──
//
// 统一封装 loading / error / freshness 状态。所有视图先尝试请求真实接口，
// 失败时由调用方回退到静态演示数据（features/monitoring/data/mock-dashboard.ts），保证前端在无服务端时仍可浏览。

import { useCallback, useEffect, useState } from "react";
import { ApiError, type ApiResult, type Freshness } from "./client";

export type ConnState = "idle" | "loading" | "live" | "error";

export interface AsyncState<T> {
  data: T | null;
  error: ApiError | Error | null;
  state: ConnState;
  freshness: Freshness | null;
  serverTime?: string;
  reload: () => void;
}

/**
 * 通用异步请求 Hook。deps 变化时自动重取；返回 reload 供手动刷新。
 * fetcher 接收 AbortSignal，切换视图/业务线时自动取消在途请求。
 */
export function useApi<T>(
  fetcher: (signal: AbortSignal) => Promise<ApiResult<T>>,
  deps: unknown[],
  options: { pollMs?: number; enabled?: boolean } = {}
): AsyncState<T> {
  const { pollMs, enabled = true } = options;
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [state, setState] = useState<ConnState>("idle");
  const [freshness, setFreshness] = useState<Freshness | null>(null);
  const [serverTime, setServerTime] = useState<string | undefined>(undefined);
  const [tick, setTick] = useState(0);

  const reload = useCallback(() => setTick((t) => t + 1), []);

  useEffect(() => {
    if (!enabled) return;
    const ctrl = new AbortController();
    let cancelled = false;
    setState((prev) => (prev === "live" ? "live" : "loading"));

    fetcher(ctrl.signal)
      .then((res) => {
        if (cancelled) return;
        setData(res.data);
        setFreshness(res.freshness);
        setServerTime(res.serverTime);
        setError(null);
        setState("live");
      })
      .catch((e: unknown) => {
        if (cancelled || (e as { name?: string })?.name === "AbortError") return;
        setError(e as Error);
        setState("error");
      });

    let timer: ReturnType<typeof setInterval> | undefined;
    if (pollMs) {
      timer = setInterval(reload, pollMs);
    }
    return () => {
      cancelled = true;
      ctrl.abort();
      if (timer) clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick, enabled, pollMs]);

  return { data, error, state, freshness, serverTime, reload };
}
