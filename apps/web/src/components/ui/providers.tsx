"use client";

import type { ReactNode } from "react";
import { ConfirmProvider } from "./confirm-dialog";
import { PromptProvider } from "./prompt-dialog";
import { ToastProvider } from "./toast";

export function UiProviders({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <ConfirmProvider>
        <PromptProvider>{children}</PromptProvider>
      </ConfirmProvider>
    </ToastProvider>
  );
}
