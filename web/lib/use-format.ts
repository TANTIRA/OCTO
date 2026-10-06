"use client";

import { useMemo } from "react";
import * as f from "./format";
import { usePreferences } from "./preferences";

/** Formatters bound to the viewer's language preference (plan §32: en + id). */
export function useFormat() {
  const { locale } = usePreferences();
  return useMemo(
    () => ({
      locale,
      num: (v: number, digits?: number) => f.num(v, digits, locale),
      money: (v: number, digits?: number) => f.money(v, digits, locale),
      moneyFull: (v: number) => f.moneyFull(v, locale),
      pct: (v: number, digits?: number) => f.pct(v, digits, locale),
      delta: (v: number, unit?: Parameters<typeof f.delta>[1], digits?: number) => f.delta(v, unit, digits, locale),
      multiple: (v: number) => f.multiple(v, locale),
      date: (iso: string) => f.date(iso, locale),
      time: f.time,
      dateTime: (iso: string) => f.dateTime(iso, locale),
      dateRange: (a: string, b: string) => f.dateRange(a, b, locale),
      ago: (iso: string, now?: Date) => f.ago(iso, now, locale),
    }),
    [locale],
  );
}
