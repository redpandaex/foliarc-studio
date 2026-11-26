"use client";

import {
  createContext,
  useCallback,
  useContext,
  useId,
  useRef,
  useState,
  type FormEvent,
  type ReactNode,
} from "react";
import { Dialog } from "@base-ui/react/dialog";
import { Button } from "./button";
import { Input } from "./input";

export type PromptRequest = {
  title: string;
  label: string;
  defaultValue: string;
  submitLabel: string;
  description?: string;
  validate?: (value: string) => string | undefined;
};

export type PromptFn = (request: PromptRequest) => Promise<string | null>;

export type PromptDialogProps = PromptRequest & {
  open: boolean;
  onSubmit: (value: string) => void;
  onDismiss: () => void;
};

export function PromptDialog({
  title,
  label,
  defaultValue,
  submitLabel,
  description,
  validate,
  open,
  onSubmit,
  onDismiss,
}: PromptDialogProps) {
  const inputId = useId();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [value, setValue] = useState(defaultValue);
  const [validationError, setValidationError] = useState<string | null>(null);

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    const err = validate?.(value);
    if (err) {
      setValidationError(err);
      return;
    }
    onSubmit(value);
  }

  return (
    <Dialog.Root
      open={open}
      modal
      onOpenChange={(nextOpen) => {
        if (!nextOpen) onDismiss();
      }}
    >
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 z-50 bg-black/50 backdrop-blur-[1px]" />
        <Dialog.Popup
          initialFocus={() => {
            const el = inputRef.current;
            if (el) {
              el.focus();
              el.select();
              return el;
            }
            return true;
          }}
          className="fixed top-1/2 left-1/2 z-50 w-[min(26rem,calc(100vw-2rem))] -translate-x-1/2 -translate-y-1/2 rounded-xl border border-border bg-surface-elevated p-5 text-foreground shadow-2xl outline-none"
        >
          <Dialog.Title className="text-sm font-semibold text-foreground">
            {title}
          </Dialog.Title>
          {description && (
            <Dialog.Description className="mt-1.5 text-xs text-muted-foreground">
              {description}
            </Dialog.Description>
          )}
          <form onSubmit={handleSubmit} className="mt-4 space-y-3">
            <div className="space-y-1.5">
              <label
                htmlFor={inputId}
                className="text-xs font-medium text-muted-foreground"
              >
                {label}
              </label>
              <Input
                ref={inputRef}
                id={inputId}
                value={value}
                aria-invalid={Boolean(validationError) || undefined}
                onChange={(e) => {
                  setValue(e.target.value);
                  if (validationError) setValidationError(null);
                }}
              />
              {validationError && (
                <p className="text-[11px] text-status-danger">{validationError}</p>
              )}
            </div>
            <div className="flex items-center justify-end gap-2 pt-1">
              <Button variant="outline" size="sm" onClick={onDismiss}>
                取消
              </Button>
              <Button type="submit" size="sm">
                {submitLabel}
              </Button>
            </div>
          </form>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

interface ActivePrompt {
  id: number;
  request: PromptRequest;
  resolve: (value: string | null) => void;
}

const PromptContext = createContext<PromptFn | null>(null);

export function PromptProvider({ children }: { children: ReactNode }) {
  const [active, setActive] = useState<ActivePrompt | null>(null);
  const activeRef = useRef<ActivePrompt | null>(null);
  const counterRef = useRef(0);

  const prompt = useCallback<PromptFn>((request) => {
    if (activeRef.current) {
      return Promise.resolve(null);
    }
    return new Promise<string | null>((resolve) => {
      counterRef.current += 1;
      const next: ActivePrompt = { id: counterRef.current, request, resolve };
      activeRef.current = next;
      setActive(next);
    });
  }, []);

  const handleDismiss = useCallback(() => {
    const current = activeRef.current;
    if (current) {
      activeRef.current = null;
      setActive(null);
      current.resolve(null);
    }
  }, []);

  const handleSubmit = useCallback((value: string) => {
    const current = activeRef.current;
    if (current) {
      activeRef.current = null;
      setActive(null);
      current.resolve(value);
    }
  }, []);

  return (
    <PromptContext.Provider value={prompt}>
      {children}
      {active && (
        <PromptDialog
          key={active.id}
          open
          title={active.request.title}
          label={active.request.label}
          defaultValue={active.request.defaultValue}
          submitLabel={active.request.submitLabel}
          description={active.request.description}
          validate={active.request.validate}
          onSubmit={handleSubmit}
          onDismiss={handleDismiss}
        />
      )}
    </PromptContext.Provider>
  );
}

export function usePrompt(): PromptFn {
  const ctx = useContext(PromptContext);
  if (!ctx) {
    throw new Error("usePrompt must be used within PromptProvider");
  }
  return ctx;
}
