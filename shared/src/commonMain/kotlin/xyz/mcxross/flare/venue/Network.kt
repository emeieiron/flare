package xyz.mcxross.flare.venue

/**
 * The exchange network a build trades on. Each venue maps it to its own endpoints and chain.
 *
 * Preferences, profiles and the transaction journal store it by [name], so names never change.
 * Flare trades mainnet only; a stored name that is no longer here (testnet's) marks state from a
 * network Flare has dropped, which [xyz.mcxross.flare.store.adoptNetwork] clears.
 */
enum class Network {
  MAINNET,
}
