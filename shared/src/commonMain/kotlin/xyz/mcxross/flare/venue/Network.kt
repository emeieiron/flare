package xyz.mcxross.flare.venue

/**
 * The exchange network a build trades on. Each venue maps it to its own endpoints and chain.
 *
 * Preferences and profiles store it by [name], so these names never change.
 */
enum class Network {
  TESTNET,
  MAINNET,
}
