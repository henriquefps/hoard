// The Markdown pipeline comes from markdown-preview (Henrique Silva, MIT):
// https://github.com/henriquefps/markdown-preview — src/components/Preview.tsx and
// MarkdownViewer.css. Reused as is: the remark/rehype plugin list and order, the sanitize schema
// (<mark> for ==highlights==, data: images), block vs inline code detection without react-markdown's
// removed `inline` prop, our own <pre>, tables in a horizontal scroller, the copy button on code
// blocks, memoised plugin arrays and components (a re-render must not disturb a text selection).
// Added for Hoard: heading ids (rehype-slug) for #anchors, code highlighting (lezer, as in the
// editor), images and links resolved against the file's folder on the SMB share.
import "katex/dist/katex.min.css"
import "./text.css"
import { CheckIcon, CopyIcon, ImageBrokenIcon } from "@phosphor-icons/react"
import { Capacitor } from "@capacitor/core"
import { createContext, memo, use, useEffect, useState, type MouseEvent, type Ref } from "react"
import ReactMarkdown, { type Components, type Options } from "react-markdown"
import { useTranslation } from "react-i18next"
import rehypeKatex from "rehype-katex"
import rehypeRaw from "rehype-raw"
import rehypeSanitize, { defaultSchema } from "rehype-sanitize"
import rehypeSlug from "rehype-slug"
import remarkEmoji from "remark-emoji"
import remarkFlexibleMarkers from "remark-flexible-markers"
import remarkGfm from "remark-gfm"
import remarkMath from "remark-math"
import remarkSupersub from "remark-supersub"
import { toast } from "sonner"
import { Button } from "@/components/ui/button"
import { resolveLink, type LinkTarget } from "@/lib/text/links"
import { cn } from "@/lib/utils"
import { HighlightedCode } from "./HighlightedCode"
import { MermaidDiagram } from "./MermaidDiagram"

/** What the rendered document needs from the page: images from the share, link navigation. */
export interface MarkdownHost {
  /** Share-relative path of the file being shown (links resolve against its folder). */
  filePath: string
  /** An `<img src>` for a file on the share, or null when it cannot be shown. */
  imageSrc: (path: string) => Promise<string | null>
  /** Follow a link to the share (another file, a folder) or outside. */
  navigate: (target: LinkTarget) => void
}

const HostContext = createContext<MarkdownHost | null>(null)

// rehype-sanitize's GitHub-like allow-list plus <mark> (remark-flexible-markers) and data: images.
const sanitizeSchema = {
  ...defaultSchema,
  tagNames: [...(defaultSchema.tagNames ?? []), "mark"],
  attributes: {
    ...defaultSchema.attributes,
    img: [...(defaultSchema.attributes?.img ?? []), "src", "alt", "title"],
    mark: [...(defaultSchema.attributes?.mark ?? []), "className"],
  },
  protocols: { ...defaultSchema.protocols, src: ["http", "https", "data"] },
}

const remarkPlugins: Options["remarkPlugins"] = [
  [remarkGfm, { singleTilde: false }],
  remarkMath,
  remarkEmoji,
  remarkSupersub,
  remarkFlexibleMarkers,
]
// Slugs after sanitize: the clobber prefix would otherwise rename every heading id.
const rehypePlugins: Options["rehypePlugins"] = [
  rehypeRaw,
  [rehypeSanitize, sanitizeSchema],
  rehypeSlug,
  rehypeKatex,
]

const Code: Components["code"] = ({ node, className, children, ...props }) => {
  const match = /language-([\w+#.-]+)/.exec(className ?? "")
  const multiLine = node?.position && node.position.start.line !== node.position.end.line
  if (!multiLine && !match) {
    return (
      <code className={className} {...props}>
        {children}
      </code>
    )
  }
  const code = String(children).replace(/\n$/, "")
  if (match?.[1] === "mermaid") return <MermaidDiagram chart={code} />
  return <CodeBlock code={code} language={match?.[1] ?? ""} />
}

const Pre: Components["pre"] = ({ children }) => <>{children}</>

const Table: Components["table"] = ({ children, ...props }) => (
  <div className="table-wrapper">
    <table {...props}>{children}</table>
  </div>
)

const Anchor: Components["a"] = ({ href, children, node: _node, ...props }) => {
  const host = use(HostContext)
  const target = href && host ? resolveLink(host.filePath, href) : ({ kind: "blocked" } as const)
  const external = target.kind === "external"
  const onClick = (event: MouseEvent<HTMLAnchorElement>) => {
    // External links: in the APK, Capacitor hands a navigation to another host to the system
    // browser; in a desktop browser, a new tab.
    if (external) return
    event.preventDefault()
    if (target.kind !== "blocked") host?.navigate(target)
  }
  return (
    <a
      {...props}
      href={external ? target.url : href}
      target={external && !Capacitor.isNativePlatform() ? "_blank" : undefined}
      rel={external ? "noreferrer noopener" : undefined}
      onClick={onClick}
    >
      {children}
    </a>
  )
}

const Image: Components["img"] = ({ src, alt, title }) => {
  const host = use(HostContext)
  const target =
    typeof src === "string" && host
      ? resolveLink(host.filePath, src)
      : ({ kind: "blocked" } as const)
  if (target.kind === "external" || target.kind === "data") {
    return (
      <img
        src={target.url}
        alt={alt ?? ""}
        title={title}
        loading="lazy"
        referrerPolicy="no-referrer"
      />
    )
  }
  if (target.kind === "share") return <ShareImage path={target.path} alt={alt} title={title} />
  return <BrokenImage alt={alt} />
}

function ShareImage({ path, alt, title }: { path: string; alt?: string; title?: string }) {
  const host = use(HostContext)
  const [state, setState] = useState<{ path: string; src: string | null } | null>(null)
  useEffect(() => {
    let cancelled = false
    void host
      ?.imageSrc(path)
      .catch(() => null)
      .then((src) => !cancelled && setState({ path, src }))
    return () => {
      cancelled = true
    }
  }, [host, path])
  if (state?.path !== path) {
    return <span className="md-image-loading" role="img" aria-label={alt ?? ""} aria-busy />
  }
  if (!state.src) return <BrokenImage alt={alt} />
  return <img src={state.src} alt={alt ?? ""} title={title} loading="lazy" />
}

function BrokenImage({ alt }: { alt?: string }) {
  return (
    <span className="md-image-broken" role="img" aria-label={alt ?? ""}>
      <ImageBrokenIcon aria-hidden />
      {alt}
    </span>
  )
}

const components: Components = { code: Code, pre: Pre, table: Table, a: Anchor, img: Image }

/**
 * Rendered Markdown (GFM tables and task lists, highlighted code, Mermaid, KaTeX, emoji,
 * ==marks==, super/subscript). Memoised: it re-renders only when the text or the host changes.
 */
export const MarkdownView = memo(function MarkdownView({
  ref,
  text,
  host,
  fontSize,
  hidden,
}: {
  ref?: Ref<HTMLDivElement>
  text: string
  host: MarkdownHost
  fontSize: number
  hidden?: boolean
}) {
  const { t } = useTranslation()
  return (
    <HostContext value={host}>
      <section
        aria-label={t("text.preview")}
        className={cn("flex h-full min-h-0 min-w-0 flex-1 flex-col", hidden && "hidden")}
      >
        <div
          ref={ref}
          className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 py-6 md:px-8"
          data-find-root
        >
          <article
            className="markdown-body mx-auto max-w-3xl"
            style={{ "--md-font-size": `${fontSize + 2}px` } as React.CSSProperties}
          >
            <ReactMarkdown
              remarkPlugins={remarkPlugins}
              rehypePlugins={rehypePlugins}
              components={components}
            >
              {text}
            </ReactMarkdown>
          </article>
        </div>
      </section>
    </HostContext>
  )
})

function CodeBlock({ code, language }: { code: string; language: string }) {
  const { t } = useTranslation()
  const [copied, setCopied] = useState(false)
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      toast.error(t("text.copyFailed"))
    }
  }
  return (
    <div className="code-block-wrapper">
      <Button
        variant="outline"
        size="icon-sm"
        onClick={() => void copy()}
        className="absolute top-2 right-2 bg-card"
        aria-label={copied ? t("text.copied") : t("text.copyCode")}
        title={copied ? t("text.copied") : t("text.copyCode")}
      >
        {copied ? <CheckIcon className="text-primary" /> : <CopyIcon />}
      </Button>
      <pre>
        <HighlightedCode code={code} language={language} />
      </pre>
    </div>
  )
}
