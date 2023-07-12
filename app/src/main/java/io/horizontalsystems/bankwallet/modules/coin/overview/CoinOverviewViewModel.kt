package io.horizontalsystems.bankwallet.modules.coin.overview

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.*
import io.horizontalsystems.bankwallet.core.providers.Translator
import io.horizontalsystems.bankwallet.entities.*
import io.horizontalsystems.bankwallet.modules.coin.CoinViewFactory
import io.horizontalsystems.marketkit.models.FullCoin
import io.horizontalsystems.marketkit.models.TokenType
import io.reactivex.disposables.CompositeDisposable

class CoinOverviewViewModel(
    private val service: CoinOverviewService,
    private val factory: CoinViewFactory,
    private val walletManager: IWalletManager,
    private val accountManager: IAccountManager,
    private val chartIndicatorManager: ChartIndicatorManager
) : ViewModel() {

    val isRefreshingLiveData = MutableLiveData<Boolean>(false)
    val overviewLiveData = MutableLiveData<CoinOverviewViewItem>()
    val viewStateLiveData = MutableLiveData<ViewState>(ViewState.Loading)

    var tokenVariants by mutableStateOf<TokenVariants?>(null)
        private set
    var showHudMessage by mutableStateOf<HudMessage?>(null)
        private set

    var chartIndicatorsState by mutableStateOf(
        ChartIndicatorsState(
            hasActiveSubscription = true,
            enabled = chartIndicatorManager.isEnabledFlow.value
        )
    )

    private val disposables = CompositeDisposable()

    private var hudMessage: HudMessage? = null
        set(value) {
            field = value
            value?.let {
                showHudMessage = it
            }
        }
    private var fullCoin = service.fullCoin
    private var activeAccount = accountManager.activeAccount
    private var activeWallets = walletManager.activeWallets

    init {
        service.coinOverviewObservable
            .subscribeIO { coinOverview ->
                isRefreshingLiveData.postValue(false)

                coinOverview.dataOrNull?.let {
                    overviewLiveData.postValue(factory.getOverviewViewItem(it))
                }

                coinOverview.viewState?.let {
                    viewStateLiveData.postValue(it)
                }
            }
            .let {
                disposables.add(it)
            }

        service.start()

        walletManager.activeWalletsUpdatedObservable
            .subscribeIO { wallets ->
                if (wallets.size > activeWallets.size) {
                    hudMessage = HudMessage(R.string.Hud_Added_To_Wallet, HudMessageType.Success, R.drawable.ic_add_to_wallet_2_24)
                } else if(wallets.size < activeWallets.size) {
                    hudMessage = HudMessage(R.string.Hud_Removed_From_Wallet, HudMessageType.Error, R.drawable.ic_empty_wallet_24)
                }

                activeWallets = wallets
                refreshTokensVariants()
            }
            .let {
                disposables.add(it)
            }

        refreshTokensVariants()
    }

    fun enableChartIndicators() {
        chartIndicatorManager.enable()
        chartIndicatorsState = chartIndicatorsState.copy(enabled = true)
    }

    fun disableChartIndicators() {
        chartIndicatorManager.disable()
        chartIndicatorsState = chartIndicatorsState.copy(enabled = false)
    }

    fun onHudMessageShown() {
        hudMessage = null
    }

    private fun refreshTokensVariants() {
        tokenVariants = getTokenVariants(fullCoin, activeAccount, activeWallets)
    }

    override fun onCleared() {
        service.stop()
        disposables.clear()
    }

    fun refresh() {
        isRefreshingLiveData.postValue(true)
        service.refresh()
    }

    fun retry() {
        isRefreshingLiveData.postValue(true)
        service.refresh()
    }

    private fun getTokenVariants(fullCoin: FullCoin, account: Account?, activeWallets: List<Wallet>): TokenVariants? {
        val items = mutableListOf<TokenVariant>()
        var type = TokenVariants.Type.Blockchains

        val accountTypeNotWatch = if (account != null && !account.isWatchAccount) {
            account.type
        } else {
            null
        }

        fullCoin.tokens.sortedBy { it.blockchainType.order }.forEach { token ->
            val canAddToWallet = accountTypeNotWatch != null
                && token.isSupported
                && token.blockchainType.supports(accountTypeNotWatch)

            when (val tokenType = token.type) {
                is TokenType.Eip20 -> {
                    val configuredToken = ConfiguredToken(token)
                    val inWallet =
                        canAddToWallet && activeWallets.any { it.configuredToken == configuredToken }
                    items.add(
                        TokenVariant(
                            value = tokenType.address.shorten(),
                            copyValue = tokenType.address,
                            imgUrl = token.blockchainType.imageUrl,
                            explorerUrl = token.blockchain.eip20TokenUrl(tokenType.address),
                            name = token.blockchain.name,
                            configuredToken = configuredToken,
                            canAddToWallet = canAddToWallet,
                            inWallet = inWallet
                        )
                    )
                }
                is TokenType.Bep2 -> {
                    val configuredToken = ConfiguredToken(token)
                    val inWallet =
                        canAddToWallet && activeWallets.any { it.configuredToken == configuredToken }
                    items.add(
                        TokenVariant(
                            value = tokenType.symbol,
                            copyValue = tokenType.symbol,
                            imgUrl = token.blockchainType.imageUrl,
                            explorerUrl = token.blockchain.bep2TokenUrl(tokenType.symbol),
                            name = token.blockchain.name,
                            configuredToken = configuredToken,
                            canAddToWallet = canAddToWallet,
                            inWallet = inWallet
                        )
                    )
                }
                is TokenType.Spl -> {
                    val configuredToken = ConfiguredToken(token)
                    val inWallet =
                        canAddToWallet && activeWallets.any { it.configuredToken == configuredToken }
                    items.add(
                        TokenVariant(
                            value = tokenType.address.shorten(),
                            copyValue = tokenType.address,
                            imgUrl = token.blockchainType.imageUrl,
                            explorerUrl = token.blockchain.eip20TokenUrl(tokenType.address),
                            name = token.blockchain.name,
                            configuredToken = configuredToken,
                            canAddToWallet = canAddToWallet,
                            inWallet = inWallet
                        )
                    )
                }
                TokenType.Native -> when (token.blockchainType.coinSettingType) {
                    CoinSettingType.derivation -> {
                        type = TokenVariants.Type.Bips

                        AccountType.Derivation.values().forEach { derivation ->
                            val coinSettings =
                                CoinSettings(mapOf(CoinSettingType.derivation to derivation.value))
                            val configuredToken = ConfiguredToken(token, coinSettings)
                            val inWallet =
                                canAddToWallet && activeWallets.any { it.configuredToken == configuredToken }
                            items.add(
                                TokenVariant(
                                    value = derivation.addressType,
                                    copyValue = null,
                                    imgUrl = token.blockchainType.imageUrl,
                                    explorerUrl = null,
                                    name = derivation.rawName,
                                    configuredToken = configuredToken,
                                    canAddToWallet = canAddToWallet,
                                    inWallet = inWallet,
                                )
                            )
                        }
                    }
                    CoinSettingType.bitcoinCashCoinType -> {
                        type = TokenVariants.Type.CoinTypes

                        BitcoinCashCoinType.values().forEach { bchCoinType ->
                            val coinSettings =
                                CoinSettings(mapOf(CoinSettingType.bitcoinCashCoinType to bchCoinType.value))
                            val configuredToken = ConfiguredToken(token, coinSettings)
                            val inWallet =
                                canAddToWallet && activeWallets.any { it.configuredToken == configuredToken }
                            items.add(
                                TokenVariant(
                                    value = Translator.getString(bchCoinType.title),
                                    copyValue = null,
                                    imgUrl = token.blockchainType.imageUrl,
                                    explorerUrl = null,
                                    name = bchCoinType.value,
                                    configuredToken = configuredToken,
                                    canAddToWallet = canAddToWallet,
                                    inWallet = inWallet
                                )
                            )
                        }
                    }
                    null -> {
                        val configuredToken = ConfiguredToken(token)
                        val inWallet =
                            canAddToWallet && activeWallets.any { it.configuredToken == configuredToken }
                        items.add(
                            TokenVariant(
                                value = Translator.getString(R.string.CoinPlatforms_Native),
                                copyValue = null,
                                imgUrl = token.blockchainType.imageUrl,
                                explorerUrl = null,
                                name = token.blockchain.name,
                                configuredToken = configuredToken,
                                canAddToWallet = canAddToWallet,
                                inWallet = inWallet
                            )
                        )
                    }
                }
                is TokenType.Unsupported -> {
                    val configuredToken = ConfiguredToken(token)
                    items.add(
                        TokenVariant(
                            value = tokenType.reference?.shorten() ?: "",
                            copyValue = tokenType.reference,
                            imgUrl = token.blockchainType.imageUrl,
                            explorerUrl = tokenType.reference?.let { token.blockchain.eip20TokenUrl(it) },
                            name = token.blockchain.name,
                            configuredToken = configuredToken,
                            canAddToWallet = false,
                            inWallet = false
                        )
                    )
                }
            }
        }

        return when {
            items.isNotEmpty() -> TokenVariants(items, type)
            else -> null
        }
    }

}

data class ChartIndicatorsState(val hasActiveSubscription: Boolean, val enabled: Boolean)

data class TokenVariants(val items: List<TokenVariant>, val type: Type) {
    enum class Type(@StringRes val titleResId: Int) {
        Blockchains(R.string.CoinPage_Blockchains),
        Bips(R.string.CoinPage_Bips),
        CoinTypes(R.string.CoinPage_CoinTypes)
    }
}
