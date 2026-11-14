/** A directory tree with item counts, built from the server's per-directory counts (GET /web/folders). */
export interface DirNode {
  name: string
  path: string // "" = the folder itself
  /** Items directly in this directory. */
  own: number
  /** Items here and below. */
  total: number
  children: DirNode[]
}

export function buildTree(dirs: readonly { path: string; items: number }[]): DirNode {
  const root: DirNode = { name: "", path: "", own: 0, total: 0, children: [] }
  for (const d of dirs) {
    let node = root
    node.total += d.items
    if (d.path === "") {
      node.own += d.items
      continue
    }
    const parts = d.path.split("/")
    parts.forEach((part, i) => {
      const path = parts.slice(0, i + 1).join("/")
      let child = node.children.find((c) => c.name === part)
      if (!child) {
        child = { name: part, path, own: 0, total: 0, children: [] }
        node.children.push(child)
      }
      child.total += d.items
      if (i === parts.length - 1) child.own += d.items
      node = child
    })
  }
  const sort = (n: DirNode) => {
    n.children.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }))
    n.children.forEach(sort)
  }
  sort(root)
  return root
}
