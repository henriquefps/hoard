import { useEffect, useRef, useState, type FormEvent } from "react"
import { useTranslation } from "react-i18next"
import {
  ResponsiveDialog,
  ResponsiveDialogBody,
  ResponsiveDialogContent,
  ResponsiveDialogDescription,
  ResponsiveDialogFooter,
  ResponsiveDialogHeader,
  ResponsiveDialogTitle,
} from "@/components/ResponsiveDialog"
import { Button } from "@/components/ui/button"
import { Field, FieldError, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { Spinner } from "@/components/ui/spinner"

/**
 * One text field in a form dialog (Dialog on desktop, bottom Drawer on phones): new folder, rename,
 * favorite label. `validate` returns an error message or null; `onSubmit` may throw a message.
 */
export function NameDialog({
  open,
  onOpenChange,
  title,
  description,
  label,
  initialValue,
  submitLabel,
  validate,
  onSubmit,
  selectBaseName = false,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  title: string
  description?: string
  label: string
  initialValue: string
  submitLabel: string
  validate: (value: string) => string | null
  onSubmit: (value: string) => Promise<void>
  /** Select the name without its extension (rename a file). */
  selectBaseName?: boolean
}) {
  const { t } = useTranslation()
  const [value, setValue] = useState(initialValue)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const [wasOpen, setWasOpen] = useState(open)
  if (open !== wasOpen) {
    // Reset while rendering (not in an effect) each time the dialog opens.
    setWasOpen(open)
    if (open) {
      setValue(initialValue)
      setError(null)
    }
  }

  useEffect(() => {
    if (!open) return
    const timer = setTimeout(() => {
      const input = inputRef.current
      if (!input) return
      input.focus()
      const dot = initialValue.lastIndexOf(".")
      input.setSelectionRange(0, selectBaseName && dot > 0 ? dot : initialValue.length)
    }, 50)
    return () => clearTimeout(timer)
  }, [open, initialValue, selectBaseName])

  async function submit(event: FormEvent) {
    event.preventDefault()
    const message = validate(value)
    if (message) {
      setError(message)
      return
    }
    setBusy(true)
    try {
      await onSubmit(value.trim())
      onOpenChange(false)
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : String(failure))
    } finally {
      setBusy(false)
    }
  }

  return (
    <ResponsiveDialog open={open} onOpenChange={onOpenChange}>
      <ResponsiveDialogContent className="sm:max-w-md">
        <form onSubmit={submit} noValidate className="flex min-h-0 flex-1 flex-col">
          <ResponsiveDialogHeader>
            <ResponsiveDialogTitle>{title}</ResponsiveDialogTitle>
            {description ? (
              <ResponsiveDialogDescription className="[overflow-wrap:anywhere]">
                {description}
              </ResponsiveDialogDescription>
            ) : (
              <ResponsiveDialogDescription className="sr-only">{label}</ResponsiveDialogDescription>
            )}
          </ResponsiveDialogHeader>
          <ResponsiveDialogBody>
            <Field data-invalid={Boolean(error)}>
              <FieldLabel htmlFor="name-dialog-input">{label}</FieldLabel>
              <Input
                id="name-dialog-input"
                ref={inputRef}
                value={value}
                onChange={(e) => {
                  setValue(e.target.value)
                  setError(null)
                }}
                aria-invalid={Boolean(error)}
                autoComplete="off"
                spellCheck={false}
                maxLength={255}
              />
              {error && <FieldError>{error}</FieldError>}
            </Field>
          </ResponsiveDialogBody>
          <ResponsiveDialogFooter>
            <Button type="submit" disabled={busy} className="md:order-last">
              {busy && <Spinner data-icon="inline-start" />}
              {submitLabel}
            </Button>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              {t("common.cancel")}
            </Button>
          </ResponsiveDialogFooter>
        </form>
      </ResponsiveDialogContent>
    </ResponsiveDialog>
  )
}
