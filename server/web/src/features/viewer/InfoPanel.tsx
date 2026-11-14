import { DownloadSimpleIcon, MapPinIcon } from "@phosphor-icons/react"
import type { ReactNode } from "react"
import { useTranslation } from "react-i18next"
import { Button } from "@/components/ui/button"
import { fileUrl } from "@/lib/api"
import { formatBytes, formatDateTime, formatDuration, formatNumber, formatShutter } from "@/lib/format"
import type { Row } from "@/lib/types"
import { wallClock } from "@/lib/types"
import type { ItemFile } from "./zoom"

export interface ItemDetail {
  item: {
    id: string
    kind: string
    mainFingerprint: string
    capture?: { local?: string; offset?: string; utc?: number; source: string }
    make?: string
    model?: string
    lens?: string
    gps?: { lat: number; lon: number; alt?: number }
    width?: number
    height?: number
    video?: { durationMs?: number; codec?: string; fps?: number; hdr?: string; width?: number; height?: number; audio?: string }
    files: (ItemFile & { locations: { source: string; folderName?: string; path?: string }[] })[]
  }
  culling: Record<string, { value: unknown; modifiedAt: number; deviceId: string }> | null
  edited: boolean
}

export interface Exposure {
  exposureTime?: number
  fNumber?: number
  iso?: number
  bias?: number
  focalLength?: number
  focalLength35?: number
}

function Section({ label, children }: { label: string; children: ReactNode }) {
  return (
    <section className="flex flex-col gap-1.5 border-b border-border px-4 py-3 last:border-b-0">
      <h3 className="eyebrow">{label}</h3>
      {children}
    </section>
  )
}

const Line = ({ children, mono }: { children: ReactNode; mono?: boolean }) => (
  <p className={mono ? "font-mono text-[0.8125rem] break-all" : "text-[0.9375rem]"}>{children}</p>
)

/** The info panel (I): when, camera and exposure, size, place, files (each downloadable), culling and who made it. */
export function InfoPanel({ row, detail, exposure }: { row: Row; detail: ItemDetail | null; exposure: Exposure | null }) {
  const { t, i18n } = useTranslation()
  const lang = i18n.language
  const it = detail?.item
  const exp = exposure ?? {}
  const expParts = [
    exp.exposureTime ? formatShutter(exp.exposureTime) : null,
    exp.fNumber ? `f/${formatNumber(exp.fNumber, lang)}` : null,
    exp.iso ? `ISO ${exp.iso}` : null,
    exp.focalLength ? `${formatNumber(exp.focalLength, lang)} mm` + (exp.focalLength35 && exp.focalLength35 !== Math.round(exp.focalLength) ? ` (${exp.focalLength35} mm ${t("info.equiv")})` : "") : null,
    exp.bias ? `${exp.bias > 0 ? "+" : ""}${formatNumber(exp.bias, lang)} EV` : null,
  ].filter(Boolean)
  const w = row.w ?? it?.width
  const h = row.h ?? it?.height
  const who = (device: string) =>
    device === "nas" ? t("info.byNas") : device.startsWith("web:") ? t("info.byWeb") : t("info.byDevice", { id: device.slice(0, 8) })

  return (
    <div className="flex flex-col text-sm">
      <Section label={t("info.when")}>
        <Line>{formatDateTime(wallClock(row), lang, true)}</Line>
        {it?.capture?.offset && <Line mono>UTC{it.capture.offset}</Line>}
        {it?.capture && it.capture.source !== "exif" && (
          <p className="text-[0.8125rem] text-muted-foreground">{t(`info.timeSource.${it.capture.source}`, { defaultValue: it.capture.source })}</p>
        )}
      </Section>
      {(row.cam || row.lens || expParts.length > 0) && (
        <Section label={t("info.camera")}>
          {row.cam && <Line>{row.cam}</Line>}
          {row.lens && <Line>{row.lens}</Line>}
          {expParts.length > 0 && <Line>{expParts.join(" · ")}</Line>}
        </Section>
      )}
      {(w || row.dur) && (
        <Section label={t("info.size")}>
          {w && h ? (
            <Line>
              {formatNumber(w, lang)} × {formatNumber(h, lang)}
              {row.k !== "video" && <span className="text-muted-foreground"> · {formatNumber(Math.round((w * h) / 1e5) / 10, lang)} MP</span>}
            </Line>
          ) : null}
          {it?.video && (
            <Line>
              {[
                it.video.durationMs ? formatDuration(it.video.durationMs) : null,
                it.video.codec?.toUpperCase(),
                it.video.fps ? `${formatNumber(Math.round(it.video.fps * 100) / 100, lang)} fps` : null,
                it.video.hdr ? `HDR ${it.video.hdr.toUpperCase()}` : null,
                it.video.audio ? t("info.audio", { codec: it.video.audio.toUpperCase() }) : null,
              ]
                .filter(Boolean)
                .join(" · ")}
            </Line>
          )}
        </Section>
      )}
      {it?.gps && (
        <Section label={t("info.place")}>
          <a
            className="inline-flex items-center gap-1.5 font-mono text-[0.8125rem] text-primary underline-offset-4 hover:underline"
            href={`https://www.openstreetmap.org/?mlat=${it.gps.lat}&mlon=${it.gps.lon}#map=15/${it.gps.lat}/${it.gps.lon}`}
            target="_blank"
            rel="noreferrer noopener"
          >
            <MapPinIcon className="size-4" />
            {it.gps.lat.toFixed(5)}, {it.gps.lon.toFixed(5)}
          </a>
        </Section>
      )}
      <Section label={t("info.culling")}>
        <Line>
          {[
            row.x === "pick" ? t("cull.picked") : row.x === "reject" ? t("cull.rejected") : t("cull.unflagged"),
            row.r ? t("cull.stars", { count: row.r }) : t("cull.noStars"),
            row.lb ? t("info.label", { label: row.lb }) : null,
          ]
            .filter(Boolean)
            .join(" · ")}
        </Line>
        {detail?.culling &&
          (["flag", "rating", "label"] as const)
            .filter((f) => detail.culling?.[f])
            .map((f) => {
              const c = detail.culling![f]
              return (
                <p key={f} className="text-[0.8125rem] text-muted-foreground">
                  {t(`info.field.${f}`)}: {who(c.deviceId)}, {formatDateTime(c.modifiedAt, lang)}
                </p>
              )
            })}
        {detail?.edited && <p className="text-[0.8125rem] text-muted-foreground">{t("info.editedInReveal")}</p>}
      </Section>
      {it && (
        <Section label={t("info.files")}>
          <ul className="flex flex-col gap-2">
            {it.files.map((f) => {
              const nas = f.locations.find((l) => l.source === "nas")
              return (
                <li key={f.fingerprint} className="flex items-start gap-2">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[0.9375rem] font-medium" title={f.name}>
                      {f.name}
                    </p>
                    <p className="truncate font-mono text-xs text-muted-foreground" title={nas?.path}>
                      {t(`info.role.${f.role}`, { defaultValue: f.role })} · {formatBytes(f.size, lang)}
                      {nas ? ` · ${nas.folderName}/${nas.path}` : ` · ${t("folders.uploads")}`}
                    </p>
                  </div>
                  <Button asChild variant="ghost" size="icon" className="shrink-0">
                    <a href={fileUrl(it.id, f.fingerprint, true)} aria-label={t("download.file", { name: f.name })} download>
                      <DownloadSimpleIcon />
                    </a>
                  </Button>
                </li>
              )
            })}
          </ul>
        </Section>
      )}
    </div>
  )
}
