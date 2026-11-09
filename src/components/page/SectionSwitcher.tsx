import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select"
import { TabsList, TabsTrigger } from "@/components/ui/tabs"

/**
 * Section navigation inside a page. Render it inside the page's <Tabs> and make an ancestor (the
 * <Tabs> itself) a container: `<Tabs className="@container" ...>`. From @2xl up it is the tab
 * strip; below, a full-width Select, so no section is hidden off-screen on a phone and there is
 * never a horizontal scroll with a hidden scrollbar. The Select only calls `onChange`, which must
 * drive the same value as <Tabs>. Use it from 4 sections up; with 2 or 3, plain tabs fit a phone.
 * Put counts and status in the item label (and a small dot on the trigger when the hidden
 * section needs attention) so the signal the strip showed is not lost.
 */
export function SectionSwitcher<T extends string>({
  sections,
  current,
  label,
  onChange,
}: {
  sections: readonly { id: T; label: string; badge?: React.ReactNode }[]
  current: T
  /** Accessible name of the Select. */
  label: string
  onChange: (id: T) => void
}) {
  return (
    <>
      <TabsList className="hidden @2xl:inline-flex">
        {sections.map((s) => (
          <TabsTrigger key={s.id} value={s.id}>
            {s.label}
            {s.badge}
          </TabsTrigger>
        ))}
      </TabsList>
      <Select value={current} onValueChange={(v) => onChange(v as T)}>
        <SelectTrigger className="w-full @2xl:hidden" aria-label={label}>
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {sections.map((s) => (
            <SelectItem key={s.id} value={s.id}>
              {s.label}
              {s.badge}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </>
  )
}
