/** The item the viewer showed last, so the grid can bring it into view when the viewer closes. */
let lastViewed: string | null = null

export const setLastViewed = (id: string) => {
  lastViewed = id
}

/** Returns the last viewed item once. */
export const takeLastViewed = () => {
  const id = lastViewed
  lastViewed = null
  return id
}
