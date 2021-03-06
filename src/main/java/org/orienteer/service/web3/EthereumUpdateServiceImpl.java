package org.orienteer.service.web3;

import com.google.common.base.Strings;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.orientechnologies.orient.core.record.impl.ODocument;
import com.orientechnologies.orient.core.type.ODocumentWrapper;
import org.apache.commons.collections4.map.HashedMap;
import org.orienteer.model.EthereumClientConfig;
import org.orienteer.model.Token;
import org.orienteer.model.TransferEvent;
import org.orienteer.model.Wallet;
import org.orienteer.service.IDBService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.utils.Convert;
import rx.Completable;
import rx.Observable;
import rx.Subscription;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Singleton
public class EthereumUpdateServiceImpl implements IEthereumUpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(EthereumUpdateServiceImpl.class);

    @Inject
    private IEthereumService ethService;

    @Inject
    private IDBService dbService;

    private CompositeSubscription compositeSubscription;

    @Override
    public void init(ODocument config) {
        if (compositeSubscription == null || !compositeSubscription.hasSubscriptions()) {
            if (compositeSubscription == null) compositeSubscription = new CompositeSubscription();
            ethService.init(config);

            compositeSubscription.addAll(
                    subscribeOnPendingTransactions(),
                    subscribeOnUpdateTransactions(),
                    subscribeOnTransferTokenEvents()
            );
            updateTokensPrice();
            updateWalletBalances();
        }
    }

    @Override
    public void destroy() {
        compositeSubscription.clear();
        ethService.destroy();
    }

    private Subscription subscribeOnUpdateTransactions() {
        EthereumClientConfig config = ethService.getConfig();
        Function<Transaction, EthBlock.Block> blockFunction = transaction -> {
            EthBlock result = null;
            try {
                result = ethService.requestBlock(transaction.getBlockNumberRaw());
            } catch (Exception ex) {
                LOG.error("Can't get transaction block: {}", transaction, ex);
            }
            return result != null ? result.getBlock() : null;
        };

        Observable<List<ODocument>> obs = ethService.getTransactionsObservable()
                .filter(t -> !dbService.isTokenAddress(t.getTo()))
                .distinct()
                .buffer(config.getTransactionsBufferDelay(), TimeUnit.SECONDS, config.getTransactionsBufferSize())
                .doOnNext(transactions-> dbService.confirmICOFarmTransactions(transactions, blockFunction))
                .map(this::getWalletsForUpdateFromTransactions)
                .flatMap(walletsMap ->  Observable.from(walletsMap.keySet())
                        .flatMap(wallet -> updateWalletBalances(wallet, walletsMap.get(wallet)))
                ).subscribeOn(Schedulers.io());

        return obs.map(docs -> docs.toArray(new ODocument[0]))
                .subscribe(
                        dbService::save,
                        (t) -> LOG.error("Can't receive new transactions!", t)
                );
    }

    private Subscription subscribeOnPendingTransactions() {
        EthereumClientConfig config = ethService.getConfig();
        Observable<List<Transaction>> obs = ethService.getPendingTransactionsObservable()
                .distinct()
                .buffer(config.getTransactionsBufferDelay(), TimeUnit.SECONDS, config.getTransactionsBufferSize())
                .subscribeOn(Schedulers.io());

        return obs.subscribe(transactions -> dbService.saveUnconfirmedICOFarmTransactions(transactions),
                (t) -> LOG.error("Can't receive pending transactions!", t));
    }


    private Subscription subscribeOnTransferTokenEvents() {
        EthereumClientConfig config = ethService.getConfig();
        List<Token> tokens = dbService.getTokens(false);

        Observable<List<ODocument>> obs = Observable.from(tokens)
                .map(t -> ethService.loadSmartContract(t.getOwner(), t))
                .flatMap(t -> t.transferEventObservable(DefaultBlockParameterName.LATEST, DefaultBlockParameterName.LATEST))
                .buffer(config.getTransactionsBufferDelay(), TimeUnit.SECONDS, config.getTransactionsBufferSize())
                .doOnNext(events -> dbService.saveTransactionsFromTransferEvents(events, true))
                .map(this::getWalletsForUpdate)
                .flatMap(walletsMap -> Observable.from(walletsMap.keySet())
                        .flatMap(wallet -> updateWalletBalances(wallet, walletsMap.get(wallet)))
                ).subscribeOn(Schedulers.io());

        return obs.map(docs -> docs.toArray(new ODocument[0]))
                .subscribe(
                        dbService::save,
                        t -> LOG.error("Can't receive token events!", t)
                );
    }

    private void updateTokensPrice() {
        createUpdatingTokenPrice()
                .subscribeOn(Schedulers.io())
                .subscribe();
    }

    private void updateWalletBalances() {
        updateWalletBalances(dbService.getWallets(), dbService.getTokens(true))
                .subscribeOn(Schedulers.io())
                .map(l -> l.toArray(new ODocument[0]))
                .subscribe(dbService::save);
    }

    private Completable createUpdatingTokenPrice() {
        return Observable.from(dbService.getTokens(false))
                .filter(token -> token.getOwner() != null)
                .flatMap(token -> {
                    Wallet owner = token.getOwner();
                    IICOFarmSmartContract contract = ethService.loadSmartContract(owner.getAddress(), token);

                    return contract.getBuyPrice()
                            .map(wei -> Convert.fromWei(new BigDecimal(wei), Convert.Unit.ETHER))
                            .toObservable()
                            .doOnNext(ether -> dbService.save(token.setEtherCost(ether)));
                }).toCompletable();
    }

    private Observable<List<ODocument>> updateWalletBalances(Wallet wallet, List<Token> tokens) {
        return updateWalletBalances(Collections.singletonList(wallet), tokens);
    }

    private Observable<List<ODocument>> updateWalletBalances(List<Wallet> wallets, List<Token> tokens) {
        return Observable.from(wallets).flatMap(wallet -> updateTokenBalances(wallet, tokens))
                .map(ODocumentWrapper::getDocument).toList();
    }

    private Observable<Wallet> updateTokenBalances(Wallet wallet, List<Token> tokens) {
        return Observable.from(tokens)
                .flatMap(token -> ethService.requestBalance(wallet.getAddress(), token)
                        .map(b -> wallet.setBalance(token.getSymbol(), b))
                        .toObservable()
                ).last();
    }

    private Map<Wallet, List<Token>> getWalletsForUpdate(List<TransferEvent> events) {
        Map<Wallet, List<Token>> walletsMap = new HashedMap<>();
        for (TransferEvent event : events) {
            List<Wallet> wallets = getWalletsFromTransactions(event.getTransaction().getFrom(), event.getTo());
            List<Token> tokens = getTokensFromTransactions(event.getTransaction().getTo());
            for (Wallet wallet : wallets) {
                walletsMap.put(wallet, tokens);
            }
        }
        return walletsMap;
    }

    private Map<Wallet, List<Token>> getWalletsForUpdateFromTransactions(List<Transaction> transactions) {
        Map<Wallet, List<Token>> walletsMap = new HashedMap<>();
        for (Transaction transaction : transactions) {
            List<Wallet> wallets = getWalletsFromTransactions(transaction.getFrom(), transaction.getTo());
            List<Token> tokens = getTokensFromTransactions(null);
            for (Wallet wallet : wallets) {
                walletsMap.put(wallet, tokens);
            }
        }
        return walletsMap;
    }

    private List<Wallet> getWalletsFromTransactions(String from, String to) {
        Set<Wallet> wallets = new HashSet<>();
        wallets.addAll(dbService.getWalletsByAddress(from));
        wallets.addAll(dbService.getWalletsByAddress(to));
        return new ArrayList<>(wallets);
    }

    private List<Token> getTokensFromTransactions(String address) {
        List<Token> tokens = dbService.getCurrencyTokens();
        if (!Strings.isNullOrEmpty(address)) {
            Token token = dbService.getTokenByAddress(address);
            if (token != null && !token.isEthereumCurrency()) {
                tokens.add(token);
            }
        }
        return tokens;
    }
}
