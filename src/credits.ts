import { decode } from 'html-entities'

/** Decode one layer of HTML entities when a credit leaves application state. */
export function decodeCredit(value: string): string {
  // Do not parse credits as HTML: preserve literal tags and incomplete references.
  return decode(value, { level: 'html5', scope: 'strict' })
}

export function decodeCredits<T extends { title?: string; artist?: string }>(value: T): T {
  return {
    ...value,
    ...(value.title === undefined ? {} : { title: decodeCredit(value.title) }),
    ...(value.artist === undefined ? {} : { artist: decodeCredit(value.artist) }),
  }
}
