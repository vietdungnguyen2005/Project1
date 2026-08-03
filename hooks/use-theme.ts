"use client";

import { useEffect, useState } from "react";

export function useTheme() {
  const [isDark, setIsDark] = useState(false);

  useEffect(() => {
    const stored = window.localStorage.getItem("v-core-theme");
    const prefersDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
    const nextIsDark = stored ? stored === "dark" : prefersDark;
    document.documentElement.classList.toggle("dark", nextIsDark);

    const hydrationFrame = window.requestAnimationFrame(() => {
      setIsDark(nextIsDark);
    });

    return () => window.cancelAnimationFrame(hydrationFrame);
  }, []);

  const toggleTheme = () => {
    setIsDark((current) => {
      const next = !current;
      document.documentElement.classList.toggle("dark", next);
      window.localStorage.setItem("v-core-theme", next ? "dark" : "light");
      return next;
    });
  };

  return { isDark, toggleTheme };
}
