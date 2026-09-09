package xyz.mcxross.flare.design

/** Display metadata only. Protocol symbols and addresses remain unchanged. */
fun assetDisplayName(symbol: String, fallback: String): String =
  when (symbol.uppercase()) {
    "BTC" -> "Bitcoin"
    "ETH" -> "Ethereum"
    "SOL" -> "Solana"
    "APT" -> "Aptos"
    "SUI" -> "Sui"
    "DOGE" -> "Dogecoin"
    "AVAX" -> "Avalanche"
    "LINK" -> "Chainlink"
    "AAVE" -> "Aave"
    "ADA" -> "Cardano"
    "AAPL" -> "Apple"
    "AMD" -> "Advanced Micro Devices"
    "AMZN" -> "Amazon"
    "ARM" -> "Arm"
    "ASML" -> "ASML"
    "GOOG",
    "GOOGL" -> "Alphabet"
    "META" -> "Meta"
    "MSFT" -> "Microsoft"
    "NFLX" -> "Netflix"
    "NVDA" -> "Nvidia"
    "TSLA" -> "Tesla"
    "XRP" -> "XRP"
    "LTC" -> "Litecoin"
    "BCH" -> "Bitcoin Cash"
    "DOT" -> "Polkadot"
    "UNI" -> "Uniswap"
    else -> fallback
  }

fun assetMonogram(symbol: String): String =
  when (symbol.uppercase()) {
    "BTC" -> "₿"
    "ETH" -> "Ξ"
    else -> symbol.take(1)
  }
