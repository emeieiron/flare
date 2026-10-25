package xyz.mcxross.flare.store

import xyz.mcxross.flare.venue.Network

/**
 * Adopts [network] for everything this installation has stored, once.
 *
 * State written on another network is unreachable on this one: a subaccount the venue has never
 * heard of, a journal row that can never be reconciled, a market cache keyed by addresses that do
 * not resolve. Left in place, the stored subaccount makes every session request fail and the
 * completed-onboarding flag stops setup from opening an account here, so the app cannot recover on
 * its own. Dropping that state is what lets setup run again.
 */
suspend fun adoptNetwork(
  network: Network,
  preferences: AppPreferences,
  database: FlareDatabase,
) {
  if (preferences.storedNetwork() == network) return
  database.transactionJournalDao().deleteOtherNetworks(network.name)
  with(database.marketCacheDao()) {
    clearMarkets()
    clearCandles()
    clearSelectedMarkets()
  }
  preferences.adoptNetwork(network)
}
