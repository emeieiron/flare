package xyz.mcxross.flare.venue.decibel

import kotlinx.coroutines.sync.Mutex

/** Shared by every Aptos writer in this app, including trading-key top-ups and owner swaps. */
class AptosSubmissionGate {
  internal val mutex = Mutex()
}
