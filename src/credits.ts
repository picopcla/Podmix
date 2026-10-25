/** Decode one layer of HTML entities when a credit leaves application state. */
export function decodeCredit(value: string): string {
  return new DOMParser().parseFromString(value, 'text/html').body.textContent ?? value
}

export function decodeCredits<T extends { title?: string; artist?: string }>(value: T): T {
  return {
    ...value,
    ...(value.title === undefined ? {} : { title: decodeCredit(value.title) }),
    ...(value.artist === undefined ? {} : { artist: decodeCredit(value.artist) }),
  }
}
