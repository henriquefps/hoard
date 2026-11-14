/** Thumbnail sizes of the gallery grid (target cell edge, CSS px). */
export const CELL_SIZES = { s: 140, m: 200, l: 300 } as const
export type CellSize = keyof typeof CELL_SIZES
