// Merge native event and request streams without letting a delayed response
// roll the display back. Do not smooth positions: real seeks must stay instant.
export function createPlayerStateOrder<T extends { stateSequence?: number }>() {
  let latest: T | undefined
  return {
    accept(state: T): boolean {
      if (state.stateSequence !== undefined && latest?.stateSequence !== undefined
        && state.stateSequence <= latest.stateSequence) return false
      latest = state
      return true
    },
    resolve(state: T): T {
      this.accept(state)
      return latest ?? state
    },
  }
}
