// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.record;

import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS;
import static com.hedera.hapi.node.base.ResponseCodeEnum.SUCCESS_BUT_MISSING_EXPECTED_OPERATION;
import static com.hedera.hapi.node.base.TokenType.FUNGIBLE_COMMON;
import static com.hedera.hapi.util.HapiUtils.asTimestamp;
import static com.hedera.node.app.hapi.utils.CommonUtils.asEvmAddress;
import static com.hedera.node.app.hapi.utils.keys.KeyUtils.IMMUTABILITY_SENTINEL_KEY;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.parseEd25519NodeAdminKeysFrom;
import static com.hedera.node.app.service.entityid.impl.schemas.V0490EntityIdSchema.ENTITY_ID_STATE_ID;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.dispatchSynthFileUpdate;
import static com.hedera.node.app.service.file.impl.schemas.V0490FileSchema.parseConfigList;
import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUpdater.END_OF_PERIOD_MEMO;
import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils.fromStakingInfo;
import static com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils.lastInstantOfPreviousPeriodFor;
import static com.hedera.node.app.service.token.impl.schemas.V0610TokenSchema.dispatchSynthNodeRewards;
import static com.hedera.node.app.spi.workflows.HandleContext.DispatchMetadata.Type.SYSTEM_TXN_CREATION_ENTITY_NUM;
import static com.hedera.node.app.spi.workflows.HandleContext.TransactionCategory.NODE;
import static com.hedera.node.app.util.FileUtilities.createFileID;
import static com.hedera.node.app.workflows.handle.HandleOutput.failInvalidStreamItems;
import static com.hedera.node.app.workflows.handle.HandleWorkflow.ALERT_MESSAGE;
import static com.hedera.node.app.workflows.handle.TransactionType.INTERNAL_TRANSACTION;
import static com.hedera.node.config.types.StreamMode.BLOCKS;
import static com.hedera.node.config.types.StreamMode.RECORDS;
import static com.swirlds.platform.system.InitTrigger.GENESIS;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Objects.requireNonNull;
import static org.hiero.consensus.node.NodeUtilities.formatNodeName;
import static org.hiero.consensus.platformstate.V0540PlatformStateSchema.PLATFORM_STATE_STATE_ID;

import com.goterl.lazysodium.utils.HexMessageEncoder;
import com.hedera.hapi.node.addressbook.NodeCreateTransactionBody;
import com.hedera.hapi.node.addressbook.NodeDeleteTransactionBody;
import com.hedera.hapi.node.addressbook.NodeUpdateTransactionBody;
import com.hedera.hapi.node.base.*;
import com.hedera.hapi.node.consensus.ConsensusCreateTopicTransactionBody;
import com.hedera.hapi.node.contract.ContractCallTransactionBody;
import com.hedera.hapi.node.contract.ContractCreateTransactionBody;
import com.hedera.hapi.node.contract.ContractFunctionResult;
import com.hedera.hapi.node.file.FileCreateTransactionBody;
import com.hedera.hapi.node.state.blockrecords.BlockInfo;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.history.ProofKey;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.token.StakingNodeInfo;
import com.hedera.hapi.node.token.CryptoCreateTransactionBody;
import com.hedera.hapi.node.token.CryptoTransferTransactionBody;
import com.hedera.hapi.node.token.TokenCreateTransactionBody;
import com.hedera.hapi.node.transaction.NodeStake;
import com.hedera.hapi.node.transaction.TransactionBody;
import com.hedera.hapi.node.transaction.TransactionReceipt;
import com.hedera.hapi.node.tss.LedgerIdNodeContribution;
import com.hedera.hapi.node.tss.LedgerIdPublicationTransactionBody;
import com.hedera.hapi.platform.state.PlatformState;
import com.hedera.node.app.blocks.BlockStreamManager;
import com.hedera.node.app.blocks.impl.PairedStreamBuilder;
import com.hedera.node.app.fees.ExchangeRateManager;
import com.hedera.node.app.records.BlockRecordManager;
import com.hedera.node.app.records.BlockRecordService;
import com.hedera.node.app.records.impl.WrappedRecordBlockHashMigration;
import com.hedera.node.app.records.schemas.V0490BlockRecordSchema;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema;
import com.hedera.node.app.service.entityid.EntityIdFactory;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.ReadableEntityIdStore;
import com.hedera.node.app.service.entityid.impl.WritableEntityIdStoreImpl;
import com.hedera.node.app.service.file.FileService;
import com.hedera.node.app.service.file.impl.FileServiceImpl;
import com.hedera.node.app.service.file.impl.schemas.V0490FileSchema;
import com.hedera.node.app.service.token.NodeRewardGroups;
import com.hedera.node.app.service.token.TokenService;
import com.hedera.node.app.service.token.impl.BlocklistParser;
import com.hedera.node.app.service.token.impl.WritableAccountStore;
import com.hedera.node.app.service.token.impl.WritableStakingInfoStore;
import com.hedera.node.app.service.token.impl.handlers.staking.EndOfStakingPeriodUtils;
import com.hedera.node.app.services.ServicesRegistry;
import com.hedera.node.app.spi.AppContext;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.app.spi.migrate.StartupNetworks;
import com.hedera.node.app.spi.records.RecordSource;
import com.hedera.node.app.spi.records.SelfNodeAccountIdManager;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.SystemContext;
import com.hedera.node.app.state.HederaRecordCache;
import com.hedera.node.app.state.recordcache.LegacyListRecordSource;
import com.hedera.node.app.store.ReadableStoreFactoryImpl;
import com.hedera.node.app.workflows.handle.Dispatch;
import com.hedera.node.app.workflows.handle.DispatchProcessor;
import com.hedera.node.app.workflows.handle.HandleOutput;
import com.hedera.node.app.workflows.handle.steps.ParentTxnFactory;
import com.hedera.node.app.workflows.handle.steps.StakePeriodChanges;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.AccountsConfig;
import com.hedera.node.config.data.BlockStreamConfig;
import com.hedera.node.config.data.BootstrapConfig;
import com.hedera.node.config.data.ConsensusConfig;
import com.hedera.node.config.data.FeesConfig;
import com.hedera.node.config.data.FilesConfig;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.config.data.NetworkAdminConfig;
import com.hedera.node.config.data.NodesConfig;
import com.hedera.node.config.data.SchedulingConfig;
import com.hedera.node.config.data.StakingConfig;
import com.hedera.node.config.types.StreamMode;
import com.hedera.node.internal.network.NodeMetadata;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.state.State;
import com.swirlds.state.spi.CommittableWritableStates;
import com.swirlds.state.spi.WritableSingletonState;
import com.swirlds.state.spi.WritableSingletonStateBase;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.LongStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.platformstate.PlatformStateService;
import org.hiero.consensus.roster.ReadableRosterStore;
import org.hiero.hapi.support.fees.FeeSchedule;

/**
 * This class is responsible for storing the system accounts created during node startup, and then creating
 * the corresponding synthetic records when a consensus time becomes available.
 */
@Singleton
@SuppressWarnings("deprecation")
public class SystemTransactions {
    private static final Logger log = LogManager.getLogger(SystemTransactions.class);

    private static final int DEFAULT_GENESIS_WEIGHT = 500;
    private static final long FIRST_RESERVED_SYSTEM_CONTRACT = 350L;
    private static final long LAST_RESERVED_SYSTEM_CONTRACT = 399L;
    private static final long FIRST_POST_SYSTEM_FILE_ENTITY = 200L;
    private static final long FIRST_MISC_ACCOUNT_NUM = 900L;
    private static final List<ServiceEndpoint> UNKNOWN_HAPI_ENDPOINT =
            List.of(V053AddressBookSchema.endpointFor("1.0.0.0", 1));

    private static final EnumSet<ResponseCodeEnum> SUCCESSES =
            EnumSet.of(SUCCESS, SUCCESS_BUT_MISSING_EXPECTED_OPERATION);
    private static final Consumer<Dispatch> DEFAULT_DISPATCH_ON_SUCCESS = dispatch -> {};

    private final InitTrigger initTrigger;
    private final BlocklistParser blocklistParser = new BlocklistParser();
    private final FileServiceImpl fileService;
    private final ParentTxnFactory parentTxnFactory;
    private final StreamMode streamMode;
    private final NetworkInfo networkInfo;
    private final DispatchProcessor dispatchProcessor;
    private final ConfigProvider configProvider;
    private final EntityIdFactory idFactory;
    private final ServicesRegistry servicesRegistry;
    private final BlockRecordManager blockRecordManager;
    private final BlockStreamManager blockStreamManager;
    private final ExchangeRateManager exchangeRateManager;
    private final HederaRecordCache recordCache;
    private final StartupNetworks startupNetworks;
    private final StakePeriodChanges stakePeriodChanges;
    private final SelfNodeAccountIdManager selfNodeAccountIdManager;
    private final WrappedRecordBlockHashMigration wrappedRecordBlockHashMigration;
    private int nextDispatchNonce = 1;

    @FunctionalInterface
    public interface StateChangeStreaming {
        void doStreamingChanges(
                @NonNull WritableStates writableStates,
                @Nullable WritableStates entityIdWritableStates,
                @NonNull Runnable action);
    }

    /**
     * Constructs a new {@link SystemTransactions}.
     */
    @Inject
    public SystemTransactions(
            @NonNull final InitTrigger initTrigger,
            @NonNull final ParentTxnFactory parentTxnFactory,
            @NonNull final FileServiceImpl fileService,
            @NonNull final NetworkInfo networkInfo,
            @NonNull final ConfigProvider configProvider,
            @NonNull final DispatchProcessor dispatchProcessor,
            @NonNull final AppContext appContext,
            @NonNull final ServicesRegistry servicesRegistry,
            @NonNull final BlockRecordManager blockRecordManager,
            @NonNull final BlockStreamManager blockStreamManager,
            @NonNull final ExchangeRateManager exchangeRateManager,
            @NonNull final HederaRecordCache recordCache,
            @NonNull final StartupNetworks startupNetworks,
            @NonNull final StakePeriodChanges stakePeriodChanges,
            @NonNull final SelfNodeAccountIdManager selfNodeAccountIdManager,
            @NonNull final WrappedRecordBlockHashMigration wrappedRecordBlockHashMigration) {
        this.initTrigger = requireNonNull(initTrigger);
        this.fileService = requireNonNull(fileService);
        this.parentTxnFactory = requireNonNull(parentTxnFactory);
        this.networkInfo = requireNonNull(networkInfo);
        this.dispatchProcessor = requireNonNull(dispatchProcessor);
        this.configProvider = requireNonNull(configProvider);
        this.streamMode = configProvider
                .getConfiguration()
                .getConfigData(BlockStreamConfig.class)
                .streamMode();
        this.idFactory = appContext.idFactory();
        this.servicesRegistry = requireNonNull(servicesRegistry);
        this.blockRecordManager = requireNonNull(blockRecordManager);
        this.blockStreamManager = requireNonNull(blockStreamManager);
        this.exchangeRateManager = requireNonNull(exchangeRateManager);
        this.recordCache = requireNonNull(recordCache);
        this.startupNetworks = requireNonNull(startupNetworks);
        this.stakePeriodChanges = requireNonNull(stakePeriodChanges);
        this.selfNodeAccountIdManager = requireNonNull(selfNodeAccountIdManager);
        this.wrappedRecordBlockHashMigration = requireNonNull(wrappedRecordBlockHashMigration);
    }

    /**
     * Used to reset the system transaction dispatch nonce at the start of a round.
     */
    public void resetNextDispatchNonce() {
        nextDispatchNonce = 1;
    }

    /**
     * Sets up genesis state for the system.
     *
     * @param now the current time
     * @param state the state to set up
     * @param stateChangeStreaming the callback to stream KV state changes
     */
    public void doGenesisSetup(
            @NonNull final Instant now,
            @NonNull final State state,
            @NonNull final StateChangeStreaming stateChangeStreaming) {
        requireNonNull(now);
        requireNonNull(state);
        final var config = configProvider.getConfiguration();
        // Do genesis setup per-service, forcing the PlatformState singleton to be externalized first
        final var writablePlatformStates = state.getWritableStates(PlatformStateService.NAME);
        stateChangeStreaming.doStreamingChanges(writablePlatformStates, null, () -> {
            // Externalize the platform state singleton as it is with a little in-place write
            final var platformStateSingleton =
                    writablePlatformStates.<PlatformState>getSingleton(PLATFORM_STATE_STATE_ID);
            platformStateSingleton.put(platformStateSingleton.get());
        });
        for (final var r : servicesRegistry.registrations()) {
            final var service = r.service();
            if (PlatformStateService.NAME.equals(service.getServiceName())) {
                continue;
            }
            // Maybe EmptyWritableStates if the service's schemas register no state definitions at all
            final var writableStates = state.getWritableStates(service.getServiceName());
            stateChangeStreaming.doStreamingChanges(
                    writableStates, null, () -> service.doGenesisSetup(writableStates, config));
        }

        final AtomicReference<Consumer<Dispatch>> onSuccess = new AtomicReference<>(DEFAULT_DISPATCH_ON_SUCCESS);
        final var systemContext = newSystemContext(
                now,
                state,
                dispatch -> onSuccess.get().accept(dispatch),
                UseReservedConsensusTimes.YES,
                TriggerStakePeriodSideEffects.NO);

        final var ledgerConfig = config.getConfigData(LedgerConfig.class);
        final var accountsConfig = config.getConfigData(AccountsConfig.class);
        final var bootstrapConfig = config.getConfigData(BootstrapConfig.class);
        final var systemKey =
                Key.newBuilder().ed25519(bootstrapConfig.genesisPublicKey()).build();
        final var systemAutoRenewPeriod = new Duration(ledgerConfig.autoRenewPeriodMaxDuration());
        // Create the system accounts
        for (int i = 1, n = ledgerConfig.numSystemAccounts(); i <= n; i++) {
            final long num = i;
            systemContext.dispatchCreation(
                    b -> b.memo("Synthetic system creation")
                            .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(systemKey)
                                    .autoRenewPeriod(systemAutoRenewPeriod)
                                    .initialBalance(
                                            num == accountsConfig.treasury() ? ledgerConfig.totalTinyBarFloat() : 0L)
                                    .build())
                            .build(),
                    i);
        }
        // Create the treasury clones
        for (long i : LongStream.rangeClosed(FIRST_POST_SYSTEM_FILE_ENTITY, ledgerConfig.numReservedSystemEntities())
                .filter(j -> j < FIRST_RESERVED_SYSTEM_CONTRACT || j > LAST_RESERVED_SYSTEM_CONTRACT)
                .toArray()) {
            systemContext.dispatchCreation(
                    b -> b.memo("Synthetic zero-balance treasury clone")
                            .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(systemKey)
                                    .autoRenewPeriod(systemAutoRenewPeriod)
                                    .build())
                            .build(),
                    i);
        }
        // Create the staking reward accounts
        for (long i : List.of(accountsConfig.stakingRewardAccount(), accountsConfig.nodeRewardAccount())) {
            systemContext.dispatchCreation(
                    b -> b.memo("Release 0.24.1 migration record")
                            .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(IMMUTABILITY_SENTINEL_KEY)
                                    .autoRenewPeriod(systemAutoRenewPeriod)
                                    .build())
                            .build(),
                    i);
        }
        // Create fee collection account
        systemContext.dispatchCreation(
                b -> b.memo("Fee collection account creation record")
                        .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                .key(IMMUTABILITY_SENTINEL_KEY)
                                .autoRenewPeriod(systemAutoRenewPeriod)
                                .build())
                        .build(),
                accountsConfig.feeCollectionAccount());
        // Create the miscellaneous accounts
        final var hederaConfig = config.getConfigData(HederaConfig.class);
        for (long i : LongStream.range(FIRST_MISC_ACCOUNT_NUM, hederaConfig.firstUserEntity())
                .toArray()) {
            systemContext.dispatchCreation(
                    b -> b.cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(systemKey)
                                    .autoRenewPeriod(systemAutoRenewPeriod)
                                    .build())
                            .build(),
                    i);
        }
        // If requested, create accounts with aliases from the blocklist
        if (accountsConfig.blocklistEnabled()) {
            for (final var info : blocklistParser.parse(accountsConfig.blocklistResource())) {
                systemContext.dispatchAdmin(b -> b.cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                        .key(systemKey)
                        .autoRenewPeriod(systemAutoRenewPeriod)
                        .receiverSigRequired(true)
                        .declineReward(true)
                        .alias(info.evmAddress())
                        .memo(info.memo())
                        .build()));
            }
        }

        // Create the address book nodes
        final var stakingConfig = config.getConfigData(StakingConfig.class);
        final var numStoredPeriods = stakingConfig.rewardHistoryNumStoredPeriods();
        final var nodeAdminKeys = parseEd25519NodeAdminKeysFrom(bootstrapConfig.nodeAdminKeysPath());
        final List<NodeStake> nodeStakes = new ArrayList<>();
        for (final var nodeInfo : networkInfo.addressBook()) {
            final var adminKey = nodeAdminKeys.getOrDefault(nodeInfo.nodeId(), systemKey);
            if (adminKey != systemKey) {
                log.info("Override admin key for node{} is :: {}", nodeInfo.nodeId(), adminKey);
            }
            final var hapiEndpoints =
                    nodeInfo.hapiEndpoints().isEmpty() ? UNKNOWN_HAPI_ENDPOINT : nodeInfo.hapiEndpoints();
            onSuccess.set(dispatch -> {
                final var stack = dispatch.stack();
                final var writableStakingInfoStore = new WritableStakingInfoStore(
                        stack.getWritableStates(TokenService.NAME),
                        new WritableEntityIdStoreImpl(stack.getWritableStates(EntityIdService.NAME)));
                if (writableStakingInfoStore.get(nodeInfo.nodeId()) == null) {
                    log.info("Creating staking info for node{}", nodeInfo.nodeId());
                    final var rewardSumHistory = new Long[numStoredPeriods + 1];
                    Arrays.fill(rewardSumHistory, 0L);
                    final var stakingNodeInfo = StakingNodeInfo.newBuilder()
                            .nodeNumber(nodeInfo.nodeId())
                            .maxStake(stakingConfig.maxStake())
                            .minStake(stakingConfig.minStake())
                            .rewardSumHistory(Arrays.asList(rewardSumHistory))
                            .weight(DEFAULT_GENESIS_WEIGHT)
                            .build();
                    writableStakingInfoStore.putAndIncrementCount(nodeInfo.nodeId(), stakingNodeInfo);
                    stack.commitFullStack();
                    nodeStakes.add(fromStakingInfo(0L, stakingNodeInfo));
                }
            });
            systemContext.dispatchCreation(
                    b -> {
                        final var nodeCreate = NodeCreateTransactionBody.newBuilder()
                                .adminKey(adminKey)
                                .accountId(nodeInfo.accountId())
                                .description(formatNodeName(nodeInfo.nodeId()))
                                .gossipEndpoint(nodeInfo.gossipEndpoints())
                                .gossipCaCertificate(nodeInfo.sigCertBytes())
                                .serviceEndpoint(hapiEndpoints)
                                .declineReward(true);
                        Optional.ofNullable(nodeInfo.grpcCertHash()).ifPresent(nodeCreate::grpcCertificateHash);
                        b.nodeCreate(nodeCreate.build());
                    },
                    nodeInfo.nodeId());
        }
        networkInfo.updateFrom(state);

        // Now that the onSuccess callback has executed for all the node create transactions, set the callback back to
        // its benign, do-nothing default
        onSuccess.set(DEFAULT_DISPATCH_ON_SUCCESS);

        // Now that the node metadata is correct, create the system files
        final var nodeStore = new ReadableStoreFactoryImpl(state).readableStore(ReadableNodeStore.class);
        fileService.createSystemEntities(systemContext, nodeStore);

        // And dispatch a node stake update transaction for mirror node benefit
        final var nodeStakeUpdate = EndOfStakingPeriodUtils.newNodeStakeUpdate(
                lastInstantOfPreviousPeriodFor(now), nodeStakes, stakingConfig, 0L, 0L, 0L, 0L);
        systemContext.dispatchAdmin(b -> b.memo(END_OF_PERIOD_MEMO).nodeStakeUpdate(nodeStakeUpdate));
        // --- PLEX ---
        // Fund the system admin account with 10 billion HBAR from the treasury before creating accounts
        final var writableEntityStates = state.getWritableStates(EntityIdService.NAME);
        final var writableTokenStates = state.getWritableStates(TokenService.NAME);
        final var accountsStore =
                new WritableAccountStore(writableTokenStates, new WritableEntityIdStoreImpl(writableEntityStates));
        final long amount = 10_000_000_000L * 100_000_000L;
        final var treasuryId = AccountID.newBuilder().accountNum(2L).build();
        final var sysAdminId = AccountID.newBuilder().accountNum(50L).build();
        final var treasuryAccount = accountsStore.get(treasuryId);
        final var sysAdminAccount = accountsStore.get(sysAdminId);
        accountsStore.put(requireNonNull(treasuryAccount)
                .copyBuilder()
                .tinybarBalance(treasuryAccount.tinybarBalance() - amount)
                .build());
        accountsStore.put(requireNonNull(sysAdminAccount)
                .copyBuilder()
                .tinybarBalance(sysAdminAccount.tinybarBalance() + amount)
                .build());
        ((CommittableWritableStates) writableTokenStates).commit();
        ((CommittableWritableStates) writableEntityStates).commit();

        setupPlexAccounts(systemContext);
        setupPlexTokens(systemContext);
        setupPlexTopics(systemContext);
        setupPlexFeeCollector(systemContext);
        setupSimpleErc20Initcode(systemContext);
        setupErc20Tokens(systemContext);
        setupSaucerSwapWhbar(systemContext);
        setupSaucerSwapWhbarToken(systemContext);
        configureSaucerSwapWhbar(systemContext);
        setupSaucerSwapV2Factory(systemContext);
        configureSaucerSwapV2Factory(systemContext);
        setupSaucerSwapV2Router(systemContext);
        setupSaucerSwapV2Quoter(systemContext);
        setupSaucerSwapV2Bootstrapper(systemContext);
        seedSaucerSwapV2Pool(systemContext);
        setupMockPullOracleInitcode(systemContext);
        setupMockPullOracle(systemContext, MOCK_SUPRA_PULL_ORACLE);
    }

    /**
     * Sets up post-upgrade state for the system.
     * @param now the current time
     * @param state the state to set up
     */
    public void doPostUpgradeSetup(@NonNull final Instant now, @NonNull final State state) {
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.YES, TriggerStakePeriodSideEffects.YES);
        final var config = configProvider.getConfiguration();

        // We update the node details file from the address book that resulted from all pre-upgrade HAPI node changes
        final var nodesConfig = config.getConfigData(NodesConfig.class);
        if (nodesConfig.enableDAB()) {
            final var nodeStore = new ReadableStoreFactoryImpl(state).readableStore(ReadableNodeStore.class);
            fileService.updateAddressBookAndNodeDetailsAfterFreeze(systemContext, nodeStore);
        }
        selfNodeAccountIdManager.setSelfNodeAccountId(networkInfo.selfNodeInfo().accountId());

        // And then we update the system files for fees schedules, throttles, override properties, and override
        // permissions from any upgrade files that are present in the configured directory
        final var filesConfig = config.getConfigData(FilesConfig.class);
        final var adminConfig = config.getConfigData(NetworkAdminConfig.class);
        final List<AutoEntityUpdate<Bytes>> autoSysFileUpdates = new ArrayList<>(List.of(
                new AutoEntityUpdate<>(
                        (ctx, bytes) ->
                                dispatchSynthFileUpdate(ctx, createFileID(filesConfig.feeSchedules(), config), bytes),
                        adminConfig.upgradeFeeSchedulesFile(),
                        SystemTransactions::parseFeeSchedules),
                new AutoEntityUpdate<>(
                        (ctx, bytes) -> dispatchSynthFileUpdate(
                                ctx, createFileID(filesConfig.throttleDefinitions(), config), bytes),
                        adminConfig.upgradeThrottlesFile(),
                        SystemTransactions::parseThrottles),
                new AutoEntityUpdate<>(
                        (ctx, bytes) -> dispatchSynthFileUpdate(
                                ctx, createFileID(filesConfig.networkProperties(), config), bytes),
                        adminConfig.upgradePropertyOverridesFile(),
                        in -> parseConfig("override network properties", in)),
                new AutoEntityUpdate<>(
                        (ctx, bytes) -> dispatchSynthFileUpdate(
                                ctx, createFileID(filesConfig.hapiPermissions(), config), bytes),
                        adminConfig.upgradePermissionOverridesFile(),
                        in -> parseConfig("override HAPI permissions", in))));
        final var feesConfig = config.getConfigData(FeesConfig.class);
        if (feesConfig.createSimpleFeeSchedule()) {
            final var simpleFeesFileId = createFileID(filesConfig.simpleFeesSchedules(), config);
            final var filesState =
                    state.getReadableStates(FileService.NAME).<FileID, File>get(V0490FileSchema.FILES_STATE_ID);
            if (filesState.get(simpleFeesFileId) == null) {
                log.info(
                        "Creating simple fee schedule file {}.{}.{} (upgrading from pre-simple-fees version)",
                        simpleFeesFileId.shardNum(),
                        simpleFeesFileId.realmNum(),
                        simpleFeesFileId.fileNum());
                fileService.fileSchema().createGenesisSimpleFeesSchedule(systemContext);
            }
            autoSysFileUpdates.add(new AutoEntityUpdate<>(
                    (ctx, bytes) -> dispatchSynthFileUpdate(
                            ctx, createFileID(filesConfig.simpleFeesSchedules(), config), bytes),
                    adminConfig.upgradeSimpleFeeSchedulesFile(),
                    SystemTransactions::parseSimpleFeesSchedules));
        }

        autoSysFileUpdates.forEach(update -> update.tryIfPresent(adminConfig.upgradeSysFilesLoc(), systemContext));
        final var autoNodeAdminKeyUpdates = new AutoEntityUpdate<Map<Long, Key>>(
                (ctx, nodeAdminKeys) -> nodeAdminKeys.forEach(
                        (nodeId, key) -> ctx.dispatchAdmin(b -> b.nodeUpdate(NodeUpdateTransactionBody.newBuilder()
                                .nodeId(nodeId)
                                .adminKey(key)
                                .build()))),
                adminConfig.upgradeNodeAdminKeysFile(),
                SystemTransactions::parseNodeAdminKeys);
        autoNodeAdminKeyUpdates.tryIfPresent(adminConfig.upgradeSysFilesLoc(), systemContext);

        // Apply effects of the jumpstart file migration (if any)
        final var migration = wrappedRecordBlockHashMigration.result();
        if (migration != null) {
            // Check if the block info in state matches the migration result; if not, overwrite
            final var writableStates = state.getWritableStates(BlockRecordService.NAME);
            final var blockInfoState = writableStates.<BlockInfo>getSingleton(V0490BlockRecordSchema.BLOCKS_STATE_ID);
            final var blockInfo = blockInfoState.get();
            boolean changed = false;
            final var builder = blockInfo.copyBuilder();
            if (!migration
                    .previousWrappedRecordBlockRootHash()
                    .equals(blockInfo.previousWrappedRecordBlockRootHash())) {
                builder.previousWrappedRecordBlockRootHash(migration.previousWrappedRecordBlockRootHash());
                changed = true;
            }
            if (!migration
                    .wrappedIntermediatePreviousBlockRootHashes()
                    .equals(blockInfo.wrappedIntermediatePreviousBlockRootHashes())) {
                builder.wrappedIntermediatePreviousBlockRootHashes(
                        migration.wrappedIntermediatePreviousBlockRootHashes());
                changed = true;
            }
            if (migration.wrappedIntermediateBlockRootsLeafCount()
                    != blockInfo.wrappedIntermediateBlockRootsLeafCount()) {
                builder.wrappedIntermediateBlockRootsLeafCount(migration.wrappedIntermediateBlockRootsLeafCount());
                changed = true;
            }
            if (changed) {
                blockInfoState.put(builder.build());
                ((WritableSingletonStateBase<BlockInfo>) blockInfoState).commit();
                log.info("Updated block info state to match migration result");
            }

            // Archive the jumpstart file so the migration doesn't run again
            final var jumpstartFilePath = wrappedRecordBlockHashMigration.jumpstartFilePath();
            if (jumpstartFilePath != null) {
                try {
                    final var archivedPath =
                            jumpstartFilePath.resolveSibling("archived_" + jumpstartFilePath.getFileName());
                    Files.move(jumpstartFilePath, archivedPath, REPLACE_EXISTING);
                    log.info("Archived jumpstart file to {}", archivedPath);
                } catch (final IOException e) {
                    log.warn("Failed to archive jumpstart file at {}", jumpstartFilePath, e);
                }
            }
        }
    }

    /**
     * Dispatches a synthetic node fee payment crypto transfer for the current staking period.
     *
     * @param state The state.
     * @param now The current time.
     * @param transfers The transfers to dispatch.
     */
    public void dispatchNodePayments(
            @NonNull final State state, @NonNull final Instant now, @NonNull final TransferList transfers) {
        requireNonNull(state);
        requireNonNull(now);
        requireNonNull(transfers);

        if (transfers.accountAmounts().isEmpty()) {
            log.info("No fees to distribute for nodes");
            return;
        }
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.NO, TriggerStakePeriodSideEffects.YES);
        systemContext.dispatchAdmin(b -> b.memo("Synthetic node fees payment")
                .cryptoTransfer(CryptoTransferTransactionBody.newBuilder()
                        .transfers(transfers)
                        .build())
                .build());
    }

    /**
     * Externalizes the ledger id and associated verification key for recursive chain-of-trust proofs.
     *
     * @param state the current state
     * @param now the consensus time for the synthetic transaction
     * @param ledgerId the new ledger id
     * @param proofKeys the proof keys for the new ledger id
     * @param targetNodeWeights the weights of the nodes in the target roster
     * @param historyProofVerificationKey the verification key for the new ledger id
     */
    public void externalizeLedgerId(
            @NonNull final State state,
            @NonNull final Instant now,
            @NonNull final Bytes ledgerId,
            @NonNull final List<ProofKey> proofKeys,
            @NonNull final SortedMap<Long, Long> targetNodeWeights,
            @NonNull final Bytes historyProofVerificationKey) {
        requireNonNull(now);
        requireNonNull(ledgerId);
        requireNonNull(proofKeys);
        requireNonNull(targetNodeWeights);
        requireNonNull(historyProofVerificationKey);
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.NO, TriggerStakePeriodSideEffects.YES);
        final List<LedgerIdNodeContribution> contributions = proofKeys.stream()
                .map(proofKey -> LedgerIdNodeContribution.newBuilder()
                        .nodeId(proofKey.nodeId())
                        .historyProofKey(proofKey.key())
                        .weight(targetNodeWeights.get(proofKey.nodeId()))
                        .build())
                .toList();
        systemContext.dispatchAdmin(b -> b.memo("Ledger id")
                .ledgerIdPublication(LedgerIdPublicationTransactionBody.newBuilder()
                        .ledgerId(ledgerId)
                        .nodeContributions(contributions)
                        .historyProofVerificationKey(historyProofVerificationKey)
                        .build()));
    }

    /**
     * Dispatches a synthetic node reward crypto transfer for the given active node accounts.
     * If the {@link NodesConfig#minPerPeriodNodeRewardUsd()} is greater than zero, inactive nodes will receive the minimum node
     * reward.
     *
     * @param state The state.
     * @param now The current time.
     * @param nodeGroups The node groups.
     * @param perNodeReward The per node reward.
     * @param nodeRewardsAccountId The node rewards account id.
     * @param rewardAccountBalance The reward account balance.
     * @param minNodeReward The minimum node reward.
     */
    public void dispatchNodeRewards(
            @NonNull final State state,
            @NonNull final Instant now,
            @NonNull final NodeRewardGroups nodeGroups,
            final long perNodeReward,
            @NonNull final AccountID nodeRewardsAccountId,
            final long rewardAccountBalance,
            final long minNodeReward) {
        requireNonNull(state);
        requireNonNull(now);
        requireNonNull(nodeGroups);
        requireNonNull(nodeRewardsAccountId);
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.NO, TriggerStakePeriodSideEffects.YES);
        final List<AccountID> activeNodeAccountIds = nodeGroups.activeNodeAccountIds();
        final List<AccountID> inactiveNodeAccountIds = nodeGroups.inactiveNodeAccountIds();
        if (activeNodeAccountIds.isEmpty() && (minNodeReward <= 0 || inactiveNodeAccountIds.isEmpty())) {
            // No eligible rewards to distribute
            return;
        }
        log.info("Found active node accounts {}", activeNodeAccountIds);
        if (minNodeReward > 0 && !inactiveNodeAccountIds.isEmpty()) {
            log.info(
                    "Found inactive node accounts {} that will receive minimum node reward {}",
                    inactiveNodeAccountIds,
                    minNodeReward);
        }
        // Check if rewardAccountBalance is enough to distribute rewards. If the balance is not enough, distribute
        // rewards to active nodes only. If the balance is enough, distribute rewards to both active and inactive nodes.
        final long activeTotal = activeNodeAccountIds.size() * perNodeReward;
        final long inactiveTotal = minNodeReward > 0 ? inactiveNodeAccountIds.size() * minNodeReward : 0L;

        if (rewardAccountBalance <= activeTotal) {
            final long activeNodeReward =
                    activeNodeAccountIds.isEmpty() ? 0 : rewardAccountBalance / activeNodeAccountIds.size();
            log.info("Balance insufficient for all, rewarding active nodes only: {} tinybars each", activeNodeReward);
            if (activeNodeReward > 0) {
                dispatchSynthNodeRewards(systemContext, nodeGroups, nodeRewardsAccountId, activeNodeReward);
            }
        } else {
            final long activeNodeReward =
                    activeNodeAccountIds.isEmpty() ? 0 : activeTotal / activeNodeAccountIds.size();
            final long totalInactiveNodesReward =
                    Math.min(Math.max(0, rewardAccountBalance - activeTotal), inactiveTotal);
            final long inactiveNodeReward =
                    inactiveNodeAccountIds.isEmpty() ? 0 : totalInactiveNodesReward / inactiveNodeAccountIds.size();
            log.info(
                    "Paying active nodes {} tinybars each, inactive nodes {} tinybars each",
                    activeNodeReward,
                    inactiveNodeReward);
            dispatchSynthNodeRewards(
                    systemContext, nodeGroups, nodeRewardsAccountId, activeNodeReward, inactiveNodeReward);
        }
    }

    public boolean dispatchTransplantUpdates(final State state, final Instant now, final long currentRoundNum) {
        requireNonNull(state);
        requireNonNull(now);
        final var readableStoreFactory = new ReadableStoreFactoryImpl(state);
        final var rosterStore = readableStoreFactory.readableStore(ReadableRosterStore.class);
        final var nodeStore = readableStoreFactory.readableStore(ReadableNodeStore.class);
        final var systemContext = newSystemContext(
                now, state, dispatch -> {}, UseReservedConsensusTimes.YES, TriggerStakePeriodSideEffects.YES);
        final var network = startupNetworks.overrideNetworkFor(currentRoundNum - 1, configProvider.getConfiguration());
        if (rosterStore.isTransplantInProgress() && network.isPresent()) {
            log.info("Roster transplant in progress, dispatching node updates for round {}", currentRoundNum - 1);
            final var overrideNodes = network.get().nodeMetadata().stream()
                    .filter(NodeMetadata::hasRosterEntry)
                    .map(NodeMetadata::rosterEntryOrThrow)
                    .map(RosterEntry::nodeId)
                    .toList();
            for (final var meta : network.get().nodeMetadata()) {
                final var node = meta.node();
                if (node == null) {
                    continue;
                }
                final var currentNode = nodeStore.get(node.nodeId());
                if (currentNode == null || currentNode.deleted()) {
                    // Node is new in the override roster, dispatch a node creation transaction
                    systemContext.dispatchCreation(
                            b -> b.memo("Synthetic node creation")
                                    .nodeCreate(NodeCreateTransactionBody.newBuilder()
                                            .adminKey(node.adminKey())
                                            .accountId(node.accountId())
                                            .description(node.description())
                                            .gossipEndpoint(node.gossipEndpoint())
                                            .gossipCaCertificate(node.gossipCaCertificate())
                                            .serviceEndpoint(node.serviceEndpoint())
                                            .declineReward(node.declineReward())
                                            .grpcProxyEndpoint(node.grpcProxyEndpoint())
                                            .grpcCertificateHash(node.grpcCertificateHash())
                                            .build())
                                    .build(),
                            node.nodeId());
                    log.info("Node {} is new in override network and is being created", node.nodeId());
                } else {
                    // Node is in the override roster and in current state, dispatch a node update transaction
                    systemContext.dispatchAdmin(b -> b.memo("Synthetic node update")
                            .nodeUpdate(NodeUpdateTransactionBody.newBuilder()
                                    .nodeId(node.nodeId())
                                    .adminKey(node.adminKey())
                                    .description(node.description())
                                    .gossipEndpoint(node.gossipEndpoint())
                                    .gossipCaCertificate(node.gossipCaCertificate())
                                    .serviceEndpoint(node.serviceEndpoint())
                                    .declineReward(node.declineReward())
                                    .grpcProxyEndpoint(node.grpcProxyEndpoint())
                                    .grpcCertificateHash(node.grpcCertificateHash())
                                    .build()));
                    log.info("Node {} in state is part of the override network and is being updated", node.nodeId());
                }
            }
            final var nextNodeId = readableStoreFactory
                    .readableStore(ReadableEntityIdStore.class)
                    .peekAtNextNodeId();
            for (var i = 0; i < nextNodeId; i++) {
                final long nodeId = i;
                final var existingNode = nodeStore.get(i);
                if (existingNode != null && !overrideNodes.contains(nodeId) && !existingNode.deleted()) {
                    // Node is in the current state but not in the override roster, mark it as deleted
                    systemContext.dispatchAdmin(b -> b.memo("Synthetic node deletion")
                            .nodeDelete(NodeDeleteTransactionBody.newBuilder()
                                    .nodeId(nodeId)
                                    .build()));
                    log.info("Node {} in state is not part of the override network and is being marked deleted", i);
                }
            }
            log.info("Roster transplant completed, node updates dispatched");
            return true;
        }
        return false;
    }

    /**
     * Defines an update based on a new representation of one or more system entities within a context.
     *
     * @param <T> the type of the update representation
     */
    @FunctionalInterface
    private interface AutoUpdate<T> {
        void doUpdate(@NonNull SystemContext systemContext, @NonNull T update);
    }

    private interface SyntheticSystemContext extends SystemContext {
        @NonNull
        HandleOutput dispatchAdminWithOutput(@NonNull Consumer<TransactionBody.Builder> spec);
    }

    /**
     * Process object encapsulating the automatic update of a system entity. Attempts to parse a
     * representation of an update from a given file and then apply it within a system context
     * using the given {@link AutoUpdate} function.
     *
     * @param updateFileName the name of the upgrade file
     * @param updateParser the function to parse the upgrade file
     * @param <T> the type of the update representation
     */
    private record AutoEntityUpdate<T>(
            @NonNull AutoUpdate<T> autoUpdate,
            @NonNull String updateFileName,
            @NonNull Function<InputStream, T> updateParser) {
        /**
         * Attempts to update the system file using the given system context if the corresponding upgrade file is
         * present at the given location and can be parsed with this update's parser.
         */
        void tryIfPresent(@NonNull final String postUpgradeLoc, @NonNull final SystemContext systemContext) {
            final var path = Paths.get(postUpgradeLoc, updateFileName);
            if (!Files.exists(path)) {
                log.info(
                        "No post-upgrade file for {} found at {}, not updating", updateFileName, path.toAbsolutePath());
                return;
            }
            try (final var fin = Files.newInputStream(path)) {
                final T update;
                try {
                    update = updateParser.apply(fin);
                } catch (Exception e) {
                    log.error("Failed to parse update file at {}", path.toAbsolutePath(), e);
                    return;
                }
                log.info("Dispatching synthetic update based on contents of {}", path.toAbsolutePath());
                autoUpdate.doUpdate(systemContext, update);
            } catch (IOException e) {
                log.error("Failed to read update file at {}", path.toAbsolutePath(), e);
            }
        }
    }

    /**
     * Returns the timestamp to use for startup work state change consensus time in the block stream.
     *
     * @param firstEventTime the timestamp of the first event in the current round
     */
    public Instant firstReservedSystemTimeFor(@NonNull final Instant firstEventTime) {
        requireNonNull(firstEventTime);
        final var config = configProvider.getConfiguration();
        final var consensusConfig = config.getConfigData(ConsensusConfig.class);
        return firstEventTime
                // Avoid overlap with a possible user transaction first in the event
                .minusNanos(1)
                // Avoid overlap with possible preceding records of this user transaction
                .minusNanos(consensusConfig.handleMaxPrecedingRecords())
                // Then back up to the first reserved system transaction time
                .minusNanos(config.getConfigData(SchedulingConfig.class).reservedSystemTxnNanos())
                // And at genesis, further step back to accommodate creating system entities
                .minusNanos(
                        initTrigger == GENESIS
                                ? (int) config.getConfigData(HederaConfig.class).firstUserEntity()
                                : 0);
    }

    /**
     * Whether a context for system transactions should use reserved prior consensus times, or pick up from
     * the time given to the transaction context factory.
     */
    private enum UseReservedConsensusTimes {
        YES,
        NO
    }

    /**
     * Whether the dispatches in a context for system transactions should trigger stake period boundary
     * side effects.
     */
    private enum TriggerStakePeriodSideEffects {
        YES,
        NO
    }

    private SystemContext newSystemContext(
            @NonNull final Instant now,
            @NonNull final State state,
            @NonNull final Consumer<Dispatch> onSuccess,
            @NonNull final UseReservedConsensusTimes useReservedConsensusTimes,
            @NonNull final TriggerStakePeriodSideEffects triggerStakePeriodSideEffects) {
        final var config = configProvider.getConfiguration();
        final boolean useReserved = useReservedConsensusTimes == UseReservedConsensusTimes.YES;
        final var firstConsTime = useReserved ? firstReservedSystemTimeFor(now) : now;
        final boolean applyStakePeriodSideEffects = triggerStakePeriodSideEffects == TriggerStakePeriodSideEffects.YES;
        // The number of dispatches we can do in this context is determined by the number of available consensus times,
        // where each dispatch also has an earlier nanosecond free for a preceding NODE_STAKE_UPDATE if this context is
        // applying stake period side effects
        final var remainingDispatches = new AtomicInteger(
                useReserved
                        ? (int) java.time.Duration.between(firstConsTime, now).toNanos()
                                / (applyStakePeriodSideEffects ? 2 : 1)
                        : 1);
        final AtomicReference<Instant> nextConsTime = new AtomicReference<>(firstConsTime);
        final var systemAdminId = idFactory.newAccountId(
                config.getConfigData(AccountsConfig.class).systemAdmin());
        // Use whatever node happens to be first in the address book as the "creator"
        final var creatorInfo = networkInfo.addressBook().getFirst();
        final var validDuration =
                new Duration(config.getConfigData(HederaConfig.class).transactionMaxValidDuration());

        return new SyntheticSystemContext() {
            @Override
            public boolean hasDispatchesRemaining() {
                return remainingDispatches.get() > 0;
            }

            @Override
            public void dispatchAdmin(@NonNull final Consumer<TransactionBody.Builder> spec) {
                dispatchAdminWithOutput(spec);
            }

            @Override
            public @NonNull HandleOutput dispatchAdminWithOutput(@NonNull final Consumer<TransactionBody.Builder> spec) {
                requireNonNull(spec);
                final var builder = TransactionBody.newBuilder()
                        .nodeAccountID(creatorInfo.accountId())
                        .transactionValidDuration(validDuration)
                        .transactionID(TransactionID.newBuilder()
                                .accountID(systemAdminId)
                                .transactionValidStart(asTimestamp(now()))
                                .nonce(nextDispatchNonce++)
                                .build());
                spec.accept(builder);
                final var body = builder.build();
                final var output = dispatch(body, 0, triggerStakePeriodSideEffects);
                final var statuses = output.preferringBlockRecordSource().identifiedReceipts().stream()
                        .map(RecordSource.IdentifiedReceipt::receipt)
                        .map(TransactionReceipt::status)
                        .toList();
                if (!SUCCESSES.containsAll(statuses)) {
                    output.preferringBlockRecordSource().forEachTxnRecord(record -> {
                        try {
                            final var result = record.contractCallResultOrThrow();
                            log.error(
                                    "Failed system contract call '{}' gasUsed={} error='{}' result=0x{}",
                                    body.memo(),
                                    result.gasUsed(),
                                    result.errorMessage(),
                                    result.contractCallResult().toHex());
                        } catch (Exception ignore) {
                        }
                    });
                    log.warn("Failed to dispatch system transaction {} - {}", body, statuses);
                } else if (body.memo().startsWith("Synthetic SaucerSwap V2 pool creation")) {
                    output.preferringBlockRecordSource().forEachTxnRecord(record -> {
                        try {
                            log.info("Successful system contract call '{}' -> {}", body.memo(), record.contractCallResultOrThrow());
                        } catch (Exception ignore) {
                        }
                    });
                }
                return output;
            }

            @Override
            public void dispatchCreation(@NonNull final Consumer<TransactionBody.Builder> spec, final long entityNum) {
                requireNonNull(spec);
                final var builder = TransactionBody.newBuilder()
                        .nodeAccountID(creatorInfo.accountId())
                        .transactionValidDuration(validDuration)
                        .transactionID(TransactionID.newBuilder()
                                .accountID(systemAdminId)
                                .transactionValidStart(asTimestamp(now()))
                                .nonce(nextDispatchNonce++)
                                .build());
                spec.accept(builder);
                dispatchCreation(builder.build(), entityNum);
            }

            @Override
            public void dispatchCreation(@NonNull final TransactionBody body, final long entityNum) {
                requireNonNull(body);
                dispatch(body, entityNum, triggerStakePeriodSideEffects);
            }

            @NonNull
            @Override
            public Configuration configuration() {
                return config;
            }

            @NonNull
            @Override
            public NetworkInfo networkInfo() {
                return networkInfo;
            }

            @NonNull
            @Override
            public Instant now() {
                return nextConsTime.get();
            }

            private HandleOutput dispatch(
                    @NonNull final TransactionBody body,
                    final long entityNum,
                    @NonNull final TriggerStakePeriodSideEffects triggerStakePeriodSideEffects) {
                if (remainingDispatches.decrementAndGet() < 0) {
                    throw new IllegalStateException("No more dispatches remaining in the system context");
                }
                final boolean applyStakePeriodSideEffects =
                        triggerStakePeriodSideEffects == TriggerStakePeriodSideEffects.YES;
                final int maxNanosUsed = applyStakePeriodSideEffects ? 2 : 1;
                final var now = nextConsTime.getAndUpdate(then -> then.plusNanos(maxNanosUsed));
                if (streamMode != BLOCKS) {
                    blockRecordManager.startUserTransaction(now, state);
                }
                final var payerId = body.transactionIDOrThrow().accountIDOrThrow();
                final var handleOutput = executeSystem(
                        state, now, creatorInfo, payerId, body, entityNum, onSuccess, applyStakePeriodSideEffects);
                if (streamMode != BLOCKS) {
                    final var records =
                            ((LegacyListRecordSource) handleOutput.recordSourceOrThrow()).precomputedRecords();
                    blockRecordManager.endUserTransaction(records.stream(), state);
                }
                if (streamMode != RECORDS) {
                    handleOutput.blockRecordSourceOrThrow().forEachItem(blockStreamManager::writeItem);
                }
                return handleOutput;
            }
        };
    }

    /**
     * Executes the scheduled transaction against the given state at the given time and returns
     * the output that should be externalized in the block stream. (And if still producing records,
     * the precomputed records.)
     * <p>
     * Never throws an exception without a fundamental breakdown of the system invariants. If
     * there is an internal error when executing the transaction, returns stream output of just the
     * scheduled transaction with a {@link ResponseCodeEnum#FAIL_INVALID} transaction result, and
     * no other side effects.
     *
     * @param state the state to execute the transaction against
     * @param now the time to execute the transaction at
     * @param creatorInfo the node info of the creator of the transaction
     * @param payerId the payer of the transaction
     * @param body the transaction to execute
     * @param nextEntityNum if not zero, the next entity number to use for the transaction
     * @param onSuccess the action to take after the transaction is successfully dispatched
     * @param applyStakePeriodSideEffects the flag indicating if this is a genesis transaction
     * @return the stream output from executing the transaction
     */
    private HandleOutput executeSystem(
            @NonNull final State state,
            @NonNull final Instant now,
            @NonNull final NodeInfo creatorInfo,
            @NonNull final AccountID payerId,
            @NonNull final TransactionBody body,
            final long nextEntityNum,
            @NonNull final Consumer<Dispatch> onSuccess,
            final boolean applyStakePeriodSideEffects) {
        final var parentTxn =
                parentTxnFactory.createSystemTxn(state, creatorInfo, now, INTERNAL_TRANSACTION, payerId, body);
        parentTxn.initBaseBuilder(exchangeRateManager.exchangeRates());
        final var createMetadata = new HandleContext.DispatchMetadata(SYSTEM_TXN_CREATION_ENTITY_NUM, nextEntityNum);
        final var dispatch = parentTxnFactory.createDispatch(
                parentTxn, parentTxn.baseBuilder(), ignore -> true, NODE, createMetadata);
        stakePeriodChanges.advanceTimeTo(parentTxn, applyStakePeriodSideEffects);
        try {
            WritableSingletonState<EntityNumber> controlledNum =
                    dispatch.stack().getWritableStates(EntityIdService.NAME).getSingleton(ENTITY_ID_STATE_ID);
            final var prevEntityNum = requireNonNull(controlledNum.get()).number();
            if (nextEntityNum != 0) {
                controlledNum.put(new EntityNumber(nextEntityNum - 1));
            }

            dispatchProcessor.processDispatch(dispatch);
            if (nextEntityNum > 1000) {
                log.info(
                        "Got status {} for plex creation 0.0.{}",
                        dispatch.streamBuilder().status(),
                        nextEntityNum);
            }
            final boolean isSuccess =
                    SUCCESSES.contains(dispatch.streamBuilder().status());
            if (!isSuccess) {
                logFailedContractDispatch(body, dispatch, nextEntityNum);
                log.error(
                        "Failed to dispatch system transaction {}{} - {}",
                        body,
                        nextEntityNum == 0 ? "" : (" for entity #" + nextEntityNum),
                        dispatch.streamBuilder().status());
            } else {
                onSuccess.accept(dispatch);
            }

            if (nextEntityNum != 0) {
                controlledNum.put(new EntityNumber(prevEntityNum));
            }

            dispatch.stack().commitFullStack();
            final var handleOutput =
                    parentTxn.stack().buildHandleOutput(parentTxn.consensusNow(), exchangeRateManager.exchangeRates());
            recordCache.addRecordSource(
                    creatorInfo.nodeId(),
                    parentTxn.txnInfo().transactionID(),
                    HederaRecordCache.DueDiligenceFailure.NO,
                    handleOutput.preferringBlockRecordSource());
            return handleOutput;
        } catch (final Exception e) {
            log.error("{} - exception thrown while handling system transaction", ALERT_MESSAGE, e);
            return failInvalidStreamItems(parentTxn, exchangeRateManager.exchangeRates(), streamMode, recordCache);
        }
    }

    private void logFailedContractDispatch(
            @NonNull final TransactionBody body, @NonNull final Dispatch dispatch, final long nextEntityNum) {
        if (!body.hasContractCreateInstance() && !body.hasContractCall()) {
            return;
        }

        final var result = contractResultOf(dispatch);
        if (body.hasContractCreateInstance()) {
            final var op = body.contractCreateInstanceOrThrow();
            log.error(
                    "Failed system contract creation '{}' target={} status={} gas={} initcodeSource={} initcodeBytes={} constructorBytes={} gasUsed={} error='{}' createdContractIds={} result=0x{}",
                    body.memo(),
                    syntheticEntityId(nextEntityNum),
                    dispatch.streamBuilder().status(),
                    op.gas(),
                    op.hasFileID() ? op.fileIDOrThrow() : "<inline>",
                    op.hasFileID() ? 0 : op.initcode().length(),
                    op.constructorParameters().length(),
                    result == null ? "<none>" : result.gasUsed(),
                    result == null ? "<none>" : result.errorMessage(),
                    result == null ? "<none>" : result.createdContractIDs(),
                    result == null ? "<none>" : result.contractCallResult().toHex());
            return;
        }

        final var op = body.contractCallOrThrow();
        log.error(
                "Failed system contract call '{}' target={} status={} gas={} amount={} inputBytes={} gasUsed={} error='{}' createdContractIds={} result=0x{}",
                body.memo(),
                op.contractIDOrThrow(),
                dispatch.streamBuilder().status(),
                op.gas(),
                op.amount(),
                op.functionParameters().length(),
                result == null ? "<none>" : result.gasUsed(),
                result == null ? "<none>" : result.errorMessage(),
                result == null ? "<none>" : result.createdContractIDs(),
                result == null ? "<none>" : result.contractCallResult().toHex());
    }

    private @Nullable ContractFunctionResult contractResultOf(@NonNull final Dispatch dispatch) {
        return switch (dispatch.streamBuilder()) {
            case RecordStreamBuilder recordBuilder ->
                    recordBuilder.hasContractResult() ? recordBuilder.contractFunctionResult() : null;
            case PairedStreamBuilder pairedBuilder -> pairedBuilder.recordStreamBuilder().hasContractResult()
                    ? pairedBuilder.recordStreamBuilder().contractFunctionResult()
                    : null;
            default -> null;
        };
    }

    private @NonNull String syntheticEntityId(final long entityNum) {
        return entityNum == 0 ? "<unassigned>" : "0.0." + entityNum;
    }

    private static Bytes parseFeeSchedules(@NonNull final InputStream in) {
        try {
            final var bytes = in.readAllBytes();
            final var feeSchedules = V0490FileSchema.parseFeeSchedules(bytes);
            return CurrentAndNextFeeSchedule.PROTOBUF.toBytes(feeSchedules);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Bytes parseSimpleFeesSchedules(@NonNull final InputStream in) {
        try {
            final var bytes = in.readAllBytes();
            final var feeSchedules = V0490FileSchema.parseSimpleFeesSchedules(bytes);
            return FeeSchedule.PROTOBUF.toBytes(feeSchedules);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Bytes parseThrottles(@NonNull final InputStream in) {
        try {
            final var json = new String(in.readAllBytes());
            return Bytes.wrap(V0490FileSchema.parseThrottleDefinitions(json));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Bytes parseConfig(@NonNull String purpose, @NonNull final InputStream in) {
        try {
            final var content = new String(in.readAllBytes());
            return parseConfigList(purpose, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<Long, Key> parseNodeAdminKeys(@NonNull final InputStream in) {
        try {
            final var json = new String(in.readAllBytes());
            return V053AddressBookSchema.parseEd25519NodeAdminKeys(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- PLEX ---

    private static final long FIRST_TOKEN_NUM = 20000L;
    private static final long FIRST_TOPIC_NUM = 30000L;
    private static final long VAULT_FARMER_ID = 333333L;
    private static final long AIRDROP_SEEDER_ID = 444444L;

    private static final String A4589187_PUBLIC_KEY =
            "ac228a873619e041648113a84f12079b8af8522073adc343e1a91594f0b1c05d";
    private static final String A4589188_PUBLIC_KEY =
            "2cfc00272518122e7c080b94fb01dae7b6ac6f0d92e5314d97583b75ac4996c0";
    private static final String A4589189_PUBLIC_KEY =
            "77afcb2b3edd975e9df3ceeafba49e9eab1e65949de2c10eb54a4fe695b4c7f8";
    private static final String A4589190_PUBLIC_KEY =
            "00239c97975a48b7a9500d30c71f4b6445e73d8b246a8d1a986bb493d50ef0c7";
    private static final String A4589192_PUBLIC_KEY =
            "96accd0d08b2a0883d5fa630e53ac8632da6578f1f049544e943bc281ae4e8ac";
    private static final String A9266133_PUBLIC_KEY =
            "03ac69bc0610b41fee3b8f66961138e8955685a723ef08d4b1d57a179548ed0cc8";
    private static final long MASTER_ID = 4589187L;
    private static final long SIMPLE_ERC20_INITCODE_ID = 1243L;
    private static final long MOCK_SUPRA_PULL_INITCODE_ID = 8877L;
    private static final long MOCK_SUPRA_PULL_ORACLE = 888777L;
    private static final long FEE_COLLECTOR_ID = 1234567L;
    private static final long SAUCERSWAP_WHBAR_CONTRACT_ID = 777776L;
    private static final long SAUCERSWAP_WHBAR_TOKEN_ID = 777777L;
    private static final long SAUCERSWAP_V2_FACTORY_ID = 777778L;
    private static final long SAUCERSWAP_V2_ROUTER_ID = 777779L;
    private static final long SAUCERSWAP_V2_QUOTER_ID = 777780L;
    private static final long SAUCERSWAP_V2_BOOTSTRAPPER_ID = 777781L;
    private static final long SAUCERSWAP_V2_POOL_ID = 777782L;
    private static final Key MASTER_KEY =
            Key.newBuilder().ed25519(Bytes.fromHex(A4589187_PUBLIC_KEY)).build();
    private static final Map<Long, Key> WELL_KNOWN_KEYS = Map.of(
            MASTER_ID,
            MASTER_KEY,
            AIRDROP_SEEDER_ID,
            MASTER_KEY,
            VAULT_FARMER_ID,
            MASTER_KEY,
            4589188L,
            Key.newBuilder().ed25519(Bytes.fromHex(A4589188_PUBLIC_KEY)).build(),
            4589189L,
            Key.newBuilder().ed25519(Bytes.fromHex(A4589189_PUBLIC_KEY)).build(),
            4589190L,
            Key.newBuilder().ed25519(Bytes.fromHex(A4589190_PUBLIC_KEY)).build(),
            4589192L,
            Key.newBuilder().ed25519(Bytes.fromHex(A4589192_PUBLIC_KEY)).build(),
            9266133L,
            Key.newBuilder().ecdsaSecp256k1(Bytes.fromHex(A9266133_PUBLIC_KEY)).build());
    private static final int NUM_TOPICS = 1;
    private static final long INITIAL_BALANCE = 100_000_000 * 100_000_000L;

    private static final String PLEX_SYMBOL = "PLEX";
    private static final String PLEX_NAME = "Lambdaplex";
    private static final long PLEX_NUMBER = 666666L;
    private static final int PLEX_DECIMALS = 6;
    private static final long PLEX_SUPPLY = BigInteger.valueOf(1_000_000_000L)
            .multiply(BigInteger.TEN.pow(PLEX_DECIMALS))
            .longValueExact();

    private static final Map<String, String> DEV_TOKEN_METADATA = new LinkedHashMap<>() {
        {
            put("BTC", "Bitcoin");
            put("ETH", "Ethereum");
            put("XRP", "XRP");
            put("BNB", "Binance Coin");
            put("SOL", "Solana");
            put("USDC", "USDC");
            put("DOGE", "Dogecoin");
            put("TRX", "TRON");
            put("ADA", "Cardano");
            put("XLM", "Stellar");
            put("HYPE", "Hyperliquid");
            put("SUI", "Sui");
            put("LINK", "Chainlink");
            put("AVAX", "Avalanche");
        }
    };
    private static final Map<String, Map<String, Long>> DEV_ERC20_METADATA = new LinkedHashMap<>() {
        {
            put("WBTC", Map.of("Wrapped Bitcoin", 111111L));
            put("WETH", Map.of("Wrapped Ethereum", 222222L));
        }
    };
    private static final int NUM_TOKENS = DEV_TOKEN_METADATA.size();

    private static final SplittableRandom RANDOM = new SplittableRandom(1_234_567L);

    private void setupPlexAccounts(SystemContext systemContext) {
        log.info("Setting up PLEX accounts");
        for (final var entry : WELL_KNOWN_KEYS.entrySet()) {
            final var accountNum = entry.getKey();
            final var key = entry.getValue();
            systemContext.dispatchCreation(
                    b -> b.memo("Synthetic plex account creation")
                            .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(key)
                                    .maxAutomaticTokenAssociations(accountNum != 9266133L ? (NUM_TOKENS + 1) : -1)
                                    .initialBalance(INITIAL_BALANCE)
                                    .autoRenewPeriod(new Duration(7776000L))
                                    .build())
                            .build(),
                    accountNum);
        }

        for (final long num : List.of(9999L, 99999L, 999999L)) {
            systemContext.dispatchCreation(
                    b -> b.memo("User bot account")
                            .cryptoCreateAccount(CryptoCreateTransactionBody.newBuilder()
                                    .key(WELL_KNOWN_KEYS.get(4589188L))
                                    .maxAutomaticTokenAssociations(111)
                                    .initialBalance(INITIAL_BALANCE)
                                    .autoRenewPeriod(new Duration(7776000L))
                                    .build())
                            .build(),
                    num);
        }
    }

    private static final String FEE_COLLECTOR_INITCODE_LOC =
            System.getenv().getOrDefault("FEE_COLLECTOR_INITCODE_LOC", "/app/LambdaplexFeeCollector.bin");
    private static final String SAUCERSWAP_WHBAR_INITCODE_LOC =
            System.getenv().getOrDefault("SAUCERSWAP_WHBAR_INITCODE_LOC", "/app/SaucerSwapWHBAR.bin");
    private static final String SAUCERSWAP_V2_FACTORY_INITCODE_LOC =
            System.getenv().getOrDefault("SAUCERSWAP_V2_FACTORY_INITCODE_LOC", "/app/SaucerSwapV2Factory.bin");
    private static final String SAUCERSWAP_V2_ROUTER_INITCODE_LOC =
            System.getenv().getOrDefault("SAUCERSWAP_V2_ROUTER_INITCODE_LOC", "/app/SaucerSwapV2SwapRouter.bin");
    private static final String SAUCERSWAP_V2_QUOTER_INITCODE_LOC =
            System.getenv().getOrDefault("SAUCERSWAP_V2_QUOTER_INITCODE_LOC", "/app/SaucerSwapV2Quoter.bin");
    private static final String SAUCERSWAP_V2_BOOTSTRAPPER_INITCODE_LOC =
            System.getenv().getOrDefault(
                    "SAUCERSWAP_V2_BOOTSTRAPPER_INITCODE_LOC", "/app/SaucerSwapV2Bootstrapper.bin");
    private static final String ERC20_CONTRACT = "SimpleERC20";
    private static final String MOCK_SUPRA_PULL_ORACLE_CONTRACT = "MockSupraOraclePull";
    private static final long SAUCERSWAP_HBAR_BOOTSTRAP_AMOUNT = 9_999_999_999_999L;
    private static final long SAUCERSWAP_USDC_BOOTSTRAP_AMOUNT = 9_333_214_185L;
    private static final long SAUCERSWAP_V2_BOOTSTRAP_LIQUIDITY = 10_343_801_999_502L;
    private static final String SAUCERSWAP_POOL_BASE_SYMBOL = "HBAR";
    private static final String SAUCERSWAP_POOL_QUOTE_SYMBOL = "USDC";
    private static final int SAUCERSWAP_V2_FEE = 1500;
    private static final int SAUCERSWAP_V2_TICK_SPACING = 60;
    private static final int SAUCERSWAP_V2_TICK_LOWER = 68460;
    private static final int SAUCERSWAP_V2_TICK_UPPER = 69660;
    private static final BigInteger SAUCERSWAP_V2_INITIAL_SQRT_PRICE_X96 =
            new BigInteger("2505414483750479311864138015696");
    private static final String SAUCERSWAP_ROUTER_OR_QUOTER_CONSTRUCTOR_ABI =
            "{\"inputs\":[{\"internalType\":\"address\",\"name\":\"_factory\",\"type\":\"address\"},{\"internalType\":\"address\",\"name\":\"_WHBAR\",\"type\":\"address\"}],\"stateMutability\":\"nonpayable\",\"type\":\"constructor\"}";
    private static final String SAUCERSWAP_BOOTSTRAPPER_CONSTRUCTOR_ABI =
            "{\"inputs\":[{\"internalType\":\"address\",\"name\":\"_factory\",\"type\":\"address\"},{\"internalType\":\"address\",\"name\":\"_WHBAR\",\"type\":\"address\"},{\"internalType\":\"address\",\"name\":\"_tokenA\",\"type\":\"address\"},{\"internalType\":\"address\",\"name\":\"_tokenB\",\"type\":\"address\"}],\"stateMutability\":\"nonpayable\",\"type\":\"constructor\"}";
    private static final String SAUCERSWAP_WHBAR_INITIALIZE_ABI =
            "{\"inputs\":[{\"internalType\":\"address\",\"name\":\"_token\",\"type\":\"address\"}],\"name\":\"initialize\",\"outputs\":[],\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";
    private static final String SAUCERSWAP_V2_ENABLE_FEE_AMOUNT_ABI =
            "{\"inputs\":[{\"internalType\":\"uint24\",\"name\":\"fee\",\"type\":\"uint24\"},{\"internalType\":\"int24\",\"name\":\"tickSpacing\",\"type\":\"int24\"}],\"name\":\"enableFeeAmount\",\"outputs\":[],\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";
    private static final String SAUCERSWAP_V2_FACTORY_CREATE_POOL_ABI =
            "{\"inputs\":[{\"internalType\":\"address\",\"name\":\"tokenA\",\"type\":\"address\"},{\"internalType\":\"address\",\"name\":\"tokenB\",\"type\":\"address\"},{\"internalType\":\"uint24\",\"name\":\"fee\",\"type\":\"uint24\"}],\"name\":\"createPool\",\"outputs\":[{\"internalType\":\"address\",\"name\":\"pool\",\"type\":\"address\"}],\"stateMutability\":\"payable\",\"type\":\"function\"}";
    private static final String SAUCERSWAP_V2_BOOTSTRAPPER_WRAP_HBAR_ABI =
            "{\"inputs\":[],\"name\":\"wrapHbar\",\"outputs\":[],\"stateMutability\":\"payable\",\"type\":\"function\"}";
    private static final String SAUCERSWAP_V2_BOOTSTRAPPER_CREATE_POOL_ABI =
            "{\"inputs\":[{\"internalType\":\"uint24\",\"name\":\"fee\",\"type\":\"uint24\"},{\"internalType\":\"uint160\",\"name\":\"sqrtPriceX96\",\"type\":\"uint160\"}],\"name\":\"createAndInitializePoolIfNecessary\",\"outputs\":[{\"internalType\":\"address\",\"name\":\"pool\",\"type\":\"address\"}],\"stateMutability\":\"payable\",\"type\":\"function\"}";
    private static final String SAUCERSWAP_V2_POOL_ASSOCIATE_TOKENS_ABI =
            "{\"inputs\":[],\"name\":\"associateTokens\",\"outputs\":[],\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";
    private static final String SAUCERSWAP_V2_POOL_INITIALIZE_ABI =
            "{\"inputs\":[{\"internalType\":\"uint160\",\"name\":\"sqrtPriceX96\",\"type\":\"uint160\"}],\"name\":\"initialize\",\"outputs\":[],\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";
    private static final String SAUCERSWAP_V2_POOL_BOOTSTRAP_LIQUIDITY_ABI =
            "{\"inputs\":[{\"internalType\":\"address\",\"name\":\"recipient\",\"type\":\"address\"},{\"internalType\":\"int24\",\"name\":\"tickLower\",\"type\":\"int24\"},{\"internalType\":\"int24\",\"name\":\"tickUpper\",\"type\":\"int24\"},{\"internalType\":\"uint128\",\"name\":\"amount\",\"type\":\"uint128\"}],\"name\":\"bootstrapLiquidity\",\"outputs\":[],\"stateMutability\":\"nonpayable\",\"type\":\"function\"}";

    private void setupPlexFeeCollector(SystemContext systemContext) {
        final byte[] initcode;
        try {
            initcode = Files.readAllBytes(resolvedInitcodePath(FEE_COLLECTOR_INITCODE_LOC));
            final var encoder = new HexMessageEncoder();
            final var unhexedBytecode = encoder.decode(new String(initcode).trim());
            final var op = ContractCreateTransactionBody.newBuilder()
                    .initcode(Bytes.wrap(unhexedBytecode))
                    .autoRenewPeriod(new Duration(7776000L))
                    .gas(1_000_000)
                    .build();
            systemContext.dispatchCreation(
                    b -> b.memo("Synthetic plex account creation")
                            .transactionID(TransactionID.newBuilder()
                                    .accountID(AccountID.newBuilder().accountNum(MASTER_ID))
                                    .build())
                            .contractCreateInstance(op)
                            .build(),
                    FEE_COLLECTOR_ID);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void setupSaucerSwapWhbar(SystemContext systemContext) {
        final var systemAdminNum = systemContext.configuration().getConfigData(AccountsConfig.class).systemAdmin();
        dispatchContractCreation(
                systemContext,
                systemAdminNum,
                SAUCERSWAP_WHBAR_CONTRACT_ID,
                "Synthetic SaucerSwap WHBAR creation",
                unhexedInitcodeAt(SAUCERSWAP_WHBAR_INITCODE_LOC),
                4_000_000L);
    }

    private void setupSaucerSwapWhbarToken(SystemContext systemContext) {
        final var op = TokenCreateTransactionBody.newBuilder()
                .supplyKey(Key.newBuilder()
                        .contractID(ContractID.newBuilder().contractNum(SAUCERSWAP_WHBAR_CONTRACT_ID).build())
                        .build())
                .tokenType(FUNGIBLE_COMMON)
                .decimals(8)
                .symbol("WHBAR")
                .name("Wrapped Hbar")
                .initialSupply(0L)
                .treasury(AccountID.newBuilder().accountNum(SAUCERSWAP_WHBAR_CONTRACT_ID).build())
                .build();
        systemContext.dispatchCreation(
                b -> b.memo("Synthetic SaucerSwap WHBAR token creation")
                        .tokenCreation(op)
                        .build(),
                SAUCERSWAP_WHBAR_TOKEN_ID);
    }

    private void configureSaucerSwapWhbar(SystemContext systemContext) {
        final var initialize = com.esaulpaugh.headlong.abi.Function.fromJson(SAUCERSWAP_WHBAR_INITIALIZE_ABI);
        final var encodedCall = initialize.encodeCallWithArgs(longZeroAddressOf(SAUCERSWAP_WHBAR_TOKEN_ID)).array();
        final var op = ContractCallTransactionBody.newBuilder()
                .contractID(ContractID.newBuilder().contractNum(SAUCERSWAP_WHBAR_CONTRACT_ID))
                .functionParameters(Bytes.wrap(encodedCall))
                .gas(1_000_000L)
                .build();
        systemContext.dispatchAdmin(
                b -> b.memo("Synthetic SaucerSwap WHBAR initialization")
                        .contractCall(op));
    }

    private void setupSaucerSwapV2Factory(SystemContext systemContext) {
        final var systemAdminNum = systemContext.configuration().getConfigData(AccountsConfig.class).systemAdmin();
        dispatchContractCreation(
                systemContext,
                systemAdminNum,
                SAUCERSWAP_V2_FACTORY_ID,
                "Synthetic SaucerSwap V2 factory creation",
                unhexedInitcodeAt(SAUCERSWAP_V2_FACTORY_INITCODE_LOC),
                10_000_000L);
    }

    private void configureSaucerSwapV2Factory(SystemContext systemContext) {
        final var enableFeeAmount =
                com.esaulpaugh.headlong.abi.Function.fromJson(SAUCERSWAP_V2_ENABLE_FEE_AMOUNT_ABI);
        final var encodedCall = enableFeeAmount
                .encodeCallWithArgs(SAUCERSWAP_V2_FEE, SAUCERSWAP_V2_TICK_SPACING)
                .array();
        final var op = ContractCallTransactionBody.newBuilder()
                .contractID(ContractID.newBuilder().contractNum(SAUCERSWAP_V2_FACTORY_ID))
                .functionParameters(Bytes.wrap(encodedCall))
                .gas(2_000_000L)
                .build();
        systemContext.dispatchAdmin(
                b -> b.memo("Synthetic SaucerSwap V2 factory configuration")
                        .contractCall(op));
    }

    private void setupSaucerSwapV2Router(SystemContext systemContext) {
        final var systemAdminNum = systemContext.configuration().getConfigData(AccountsConfig.class).systemAdmin();
        final var initcodeWithArgs = initcodeWithConstructorArgs(
                unhexedInitcodeAt(SAUCERSWAP_V2_ROUTER_INITCODE_LOC),
                SAUCERSWAP_ROUTER_OR_QUOTER_CONSTRUCTOR_ABI,
                longZeroAddressOf(SAUCERSWAP_V2_FACTORY_ID),
                longZeroAddressOf(SAUCERSWAP_WHBAR_CONTRACT_ID));
        dispatchContractCreation(
                systemContext,
                systemAdminNum,
                SAUCERSWAP_V2_ROUTER_ID,
                "Synthetic SaucerSwap V2 router creation",
                initcodeWithArgs,
                14_000_000L);
    }

    private void setupSaucerSwapV2Quoter(SystemContext systemContext) {
        final var systemAdminNum = systemContext.configuration().getConfigData(AccountsConfig.class).systemAdmin();
        final var initcodeWithArgs = initcodeWithConstructorArgs(
                unhexedInitcodeAt(SAUCERSWAP_V2_QUOTER_INITCODE_LOC),
                SAUCERSWAP_ROUTER_OR_QUOTER_CONSTRUCTOR_ABI,
                longZeroAddressOf(SAUCERSWAP_V2_FACTORY_ID),
                longZeroAddressOf(SAUCERSWAP_WHBAR_CONTRACT_ID));
        dispatchContractCreation(
                systemContext,
                systemAdminNum,
                SAUCERSWAP_V2_QUOTER_ID,
                "Synthetic SaucerSwap V2 quoter creation",
                initcodeWithArgs,
                16_000_000L);
    }

    private void setupSaucerSwapV2Bootstrapper(SystemContext systemContext) {
        final var systemAdminNum = systemContext.configuration().getConfigData(AccountsConfig.class).systemAdmin();
        final var initcodeWithArgs = initcodeWithConstructorArgs(
                unhexedInitcodeAt(SAUCERSWAP_V2_BOOTSTRAPPER_INITCODE_LOC),
                SAUCERSWAP_BOOTSTRAPPER_CONSTRUCTOR_ABI,
                longZeroAddressOf(SAUCERSWAP_V2_FACTORY_ID),
                longZeroAddressOf(SAUCERSWAP_WHBAR_CONTRACT_ID),
                longZeroAddressOf(tokenNumForSymbol(SAUCERSWAP_POOL_QUOTE_SYMBOL)),
                longZeroAddressOf(SAUCERSWAP_WHBAR_TOKEN_ID));
        dispatchContractCreation(
                systemContext,
                systemAdminNum,
                SAUCERSWAP_V2_BOOTSTRAPPER_ID,
                "Synthetic SaucerSwap V2 bootstrapper creation",
                initcodeWithArgs,
                12_000_000L);
    }

    private void seedSaucerSwapV2Pool(SystemContext systemContext) {
        final var syntheticSystemContext = (SyntheticSystemContext) systemContext;
        final var createPool =
                com.esaulpaugh.headlong.abi.Function.fromJson(SAUCERSWAP_V2_BOOTSTRAPPER_CREATE_POOL_ABI);
        final var createPoolCall = createPool
                .encodeCallWithArgs(SAUCERSWAP_V2_FEE, SAUCERSWAP_V2_INITIAL_SQRT_PRICE_X96)
                .array();
        final var createPoolOp = ContractCallTransactionBody.newBuilder()
                .contractID(ContractID.newBuilder().contractNum(SAUCERSWAP_V2_BOOTSTRAPPER_ID))
                .functionParameters(Bytes.wrap(createPoolCall))
                .gas(18_000_000L)
                .build();
        final var createPoolOutput = syntheticSystemContext.dispatchAdminWithOutput(
                b -> b.memo("Synthetic SaucerSwap V2 pool creation for HBAR-USDC")
                        .contractCall(createPoolOp));
        final var poolId = createdContractNumFrom(createPoolOutput, "Synthetic SaucerSwap V2 pool creation for HBAR-USDC");

        final var wrapHbar =
                com.esaulpaugh.headlong.abi.Function.fromJson(SAUCERSWAP_V2_BOOTSTRAPPER_WRAP_HBAR_ABI);
        final var wrapHbarOp = ContractCallTransactionBody.newBuilder()
                .contractID(ContractID.newBuilder().contractNum(SAUCERSWAP_V2_BOOTSTRAPPER_ID))
                .functionParameters(Bytes.wrap(wrapHbar.encodeCallWithArgs().array()))
                .amount(SAUCERSWAP_HBAR_BOOTSTRAP_AMOUNT)
                .gas(4_000_000L)
                .build();
        systemContext.dispatchAdmin(
                b -> b.memo("Synthetic SaucerSwap V2 WHBAR bootstrap funding")
                        .contractCall(wrapHbarOp));

        final var usdcTokenId = TokenID.newBuilder().tokenNum(tokenNumForSymbol(SAUCERSWAP_POOL_QUOTE_SYMBOL)).build();
        final var usdcTransfer = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(usdcTokenId)
                        .transfers(
                                AccountAmount.newBuilder()
                                        .accountID(AccountID.newBuilder().accountNum(MASTER_ID))
                                        .amount(-SAUCERSWAP_USDC_BOOTSTRAP_AMOUNT)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(AccountID.newBuilder().accountNum(poolId))
                                        .amount(SAUCERSWAP_USDC_BOOTSTRAP_AMOUNT)
                                        .build())
                        .build())
                .build();
        systemContext.dispatchAdmin(
                b -> b.memo("Synthetic SaucerSwap V2 USDC bootstrap funding")
                        .cryptoTransfer(usdcTransfer));

        final var whbarTokenId = TokenID.newBuilder().tokenNum(SAUCERSWAP_WHBAR_TOKEN_ID).build();
        final var whbarTransfer = CryptoTransferTransactionBody.newBuilder()
                .tokenTransfers(TokenTransferList.newBuilder()
                        .token(whbarTokenId)
                        .transfers(
                                AccountAmount.newBuilder()
                                        .accountID(AccountID.newBuilder().accountNum(SAUCERSWAP_V2_BOOTSTRAPPER_ID))
                                        .amount(-SAUCERSWAP_HBAR_BOOTSTRAP_AMOUNT)
                                        .build(),
                                AccountAmount.newBuilder()
                                        .accountID(AccountID.newBuilder().accountNum(poolId))
                                        .amount(SAUCERSWAP_HBAR_BOOTSTRAP_AMOUNT)
                                        .build())
                        .build())
                .build();
        systemContext.dispatchAdmin(
                b -> b.memo("Synthetic SaucerSwap V2 WHBAR pool funding")
                        .cryptoTransfer(whbarTransfer));

        final var bootstrapLiquidity =
                com.esaulpaugh.headlong.abi.Function.fromJson(SAUCERSWAP_V2_POOL_BOOTSTRAP_LIQUIDITY_ABI);
        final var bootstrapLiquidityCall = bootstrapLiquidity
                .encodeCallWithArgs(
                        longZeroAddressOf(MASTER_ID),
                        SAUCERSWAP_V2_TICK_LOWER,
                        SAUCERSWAP_V2_TICK_UPPER,
                        BigInteger.valueOf(SAUCERSWAP_V2_BOOTSTRAP_LIQUIDITY))
                .array();
        final var bootstrapLiquidityOp = ContractCallTransactionBody.newBuilder()
                .contractID(ContractID.newBuilder().contractNum(poolId))
                .functionParameters(Bytes.wrap(bootstrapLiquidityCall))
                .gas(8_000_000L)
                .build();
        systemContext.dispatchAdmin(
                b -> b.memo("Synthetic SaucerSwap V2 initial liquidity bootstrap")
                        .contractCall(bootstrapLiquidityOp));
    }

    private long createdContractNumFrom(@NonNull final HandleOutput output, @NonNull final String memo) {
        final long[] createdContractNum = {0L};
        output.preferringBlockRecordSource().forEachTxnRecord(record -> {
            try {
                final var createdIds = record.contractCallResultOrThrow().createdContractIDs();
                if (!createdIds.isEmpty() && createdContractNum[0] == 0L) {
                    createdContractNum[0] = createdIds.get(0).contractNumOrThrow();
                }
            } catch (Exception ignore) {
            }
        });
        if (createdContractNum[0] == 0L) {
            throw new IllegalStateException("No created contract id recorded for " + memo);
        }
        return createdContractNum[0];
    }

    private byte[] unhexedInitcodeAt(@NonNull final String location) {
        try {
            final var initcode = Files.readAllBytes(resolvedInitcodePath(location));
            final var encoder = new HexMessageEncoder();
            return encoder.decode(new String(initcode).trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private java.nio.file.Path resolvedInitcodePath(@NonNull final String location) {
        final var configured = Paths.get(location);
        if (Files.exists(configured)) {
            return configured;
        }
        final var fileName = configured.getFileName();
        if (fileName != null) {
            for (var dir = Paths.get("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
                final var candidate = dir.resolve(fileName.toString());
                if (Files.exists(candidate)) {
                    return candidate;
                }
            }
        }
        return configured;
    }

    private byte[] initcodeWithConstructorArgs(
            @NonNull final byte[] initcode, @NonNull final String constructorAbi, @NonNull final Object... args) {
        final var constructor = com.esaulpaugh.headlong.abi.Function.fromJson(constructorAbi);
        final var encodedArgs = constructor.encodeCallWithArgs(args).array();
        final var initcodeWithArgs = Arrays.copyOf(initcode, initcode.length + encodedArgs.length - 4);
        System.arraycopy(encodedArgs, 4, initcodeWithArgs, initcode.length, encodedArgs.length - 4);
        return initcodeWithArgs;
    }

    private void dispatchContractCreation(
            @NonNull final SystemContext systemContext,
            final long payerNum,
            final long contractNum,
            @NonNull final String memo,
            @NonNull final byte[] initcode,
            final long gas) {
        final var op = ContractCreateTransactionBody.newBuilder()
                .initcode(Bytes.wrap(initcode))
                .autoRenewPeriod(new Duration(7776000L))
                .gas(gas)
                .build();
        systemContext.dispatchCreation(
                b -> b.memo(memo)
                        .transactionID(TransactionID.newBuilder()
                                .accountID(AccountID.newBuilder().accountNum(payerNum))
                                .build())
                        .contractCreateInstance(op)
                        .build(),
                contractNum);
    }

    private void setupSimpleErc20Initcode(SystemContext systemContext) {
        final byte[] initcode;
        try {
            final var loc = resolvedSiblingInitcodePath(FEE_COLLECTOR_INITCODE_LOC, ERC20_CONTRACT + ".bin");
            initcode = Files.readAllBytes(loc);
            final var op = FileCreateTransactionBody.newBuilder()
                    .contents(Bytes.wrap(initcode))
                    .build();
            systemContext.dispatchCreation(
                    b -> b.memo("Simple ERC-20 initcode creation")
                            .transactionID(TransactionID.newBuilder()
                                    .accountID(AccountID.newBuilder().accountNum(MASTER_ID))
                                    .build())
                            .fileCreate(op)
                            .build(),
                    SIMPLE_ERC20_INITCODE_ID);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void setupMockPullOracleInitcode(SystemContext systemContext) {
        final byte[] initcode;
        try {
            final var loc = resolvedSiblingInitcodePath(FEE_COLLECTOR_INITCODE_LOC, MOCK_SUPRA_PULL_ORACLE_CONTRACT + ".bin");
            initcode = Files.readAllBytes(loc);
            final var op = FileCreateTransactionBody.newBuilder()
                    .contents(Bytes.wrap(initcode))
                    .build();
            systemContext.dispatchCreation(
                    b -> b.memo("Mock Supra pull oracle initcode creation")
                            .transactionID(TransactionID.newBuilder()
                                    .accountID(AccountID.newBuilder().accountNum(MASTER_ID))
                                    .build())
                            .fileCreate(op)
                            .build(),
                    MOCK_SUPRA_PULL_INITCODE_ID);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private java.nio.file.Path resolvedSiblingInitcodePath(
            @NonNull final String anchorLocation, @NonNull final String siblingFileName) {
        final var anchor = resolvedInitcodePath(anchorLocation);
        final var parent = anchor.getParent();
        if (parent != null) {
            final var sibling = parent.resolve(siblingFileName);
            if (Files.exists(sibling)) {
                return sibling;
            }
        }
        return resolvedInitcodePath(siblingFileName);
    }

    private static final String CONSTRUCTOR_ABI =
            "{\"inputs\":[{\"internalType\":\"string\",\"name\":\"name_\",\"type\":\"string\"},{\"internalType\":\"string\",\"name\":\"symbol_\",\"type\":\"string\"}],\"stateMutability\":\"nonpayable\",\"type\":\"constructor\"}";

    private void setupErc20(SystemContext systemContext, String name, String symbol, long contractId) {
        final var f = com.esaulpaugh.headlong.abi.Function.fromJson(CONSTRUCTOR_ABI);
        final var encodedCall = f.encodeCallWithArgs(name, symbol).array();
        final var op = ContractCreateTransactionBody.newBuilder()
                .fileID(FileID.newBuilder().fileNum(SIMPLE_ERC20_INITCODE_ID))
                .constructorParameters(Bytes.wrap(Arrays.copyOfRange(encodedCall, 4, encodedCall.length)))
                .autoRenewPeriod(new Duration(7776000L))
                .gas(4_000_000)
                .build();
        systemContext.dispatchCreation(
                b -> b.memo("Synth ERC-20 " + symbol + " creation")
                        .transactionID(TransactionID.newBuilder()
                                .accountID(AccountID.newBuilder().accountNum(MASTER_ID))
                                .build())
                        .contractCreateInstance(op)
                        .build(),
                contractId);
    }

    private void setupMockPullOracle(SystemContext systemContext, long contractId) {
        final var op = ContractCreateTransactionBody.newBuilder()
                .fileID(FileID.newBuilder().fileNum(MOCK_SUPRA_PULL_INITCODE_ID))
                .autoRenewPeriod(new Duration(7776000L))
                .gas(4_000_000)
                .build();
        systemContext.dispatchCreation(
                b -> b.memo("Mock Supra pull oracle creation")
                        .transactionID(TransactionID.newBuilder()
                                .accountID(AccountID.newBuilder().accountNum(MASTER_ID))
                                .build())
                        .contractCreateInstance(op)
                        .build(),
                contractId);
    }

    private void setupPlexTokens(SystemContext systemContext) {
        final var tokenTreasuryId = AccountID.newBuilder().accountNum(MASTER_ID).build();
        final var number = new AtomicLong(FIRST_TOKEN_NUM);
        DEV_TOKEN_METADATA.forEach((s, name) -> {
            final long n = number.getAndIncrement();
            final var symbol = s.toUpperCase();
            final var op = TokenCreateTransactionBody.newBuilder()
                    .supplyKey(MASTER_KEY)
                    .tokenType(FUNGIBLE_COMMON)
                    .decimals(symbol.equals("USDC") ? 6 : RANDOM.nextInt(2, 11))
                    .symbol(symbol)
                    .name(name)
                    .initialSupply(Long.MAX_VALUE)
                    .treasury(tokenTreasuryId)
                    .build();
            systemContext.dispatchCreation(
                    b -> b.memo("Synthetic plex token creation")
                            .tokenCreation(op)
                            .build(),
                    n);
        });
        final var op = TokenCreateTransactionBody.newBuilder()
                .supplyKey(MASTER_KEY)
                .tokenType(FUNGIBLE_COMMON)
                .decimals(PLEX_DECIMALS)
                .symbol(PLEX_SYMBOL)
                .name(PLEX_NAME)
                .initialSupply(PLEX_SUPPLY)
                .treasury(tokenTreasuryId)
                .build();
        systemContext.dispatchCreation(
                b -> b.memo("Synthetic PLEX token creation").tokenCreation(op).build(), PLEX_NUMBER);
    }

    private void setupErc20Tokens(SystemContext systemContext) {
        DEV_ERC20_METADATA.forEach((s, meta) -> {
            final var entry = meta.entrySet().iterator().next();
            setupErc20(systemContext, entry.getKey(), s, entry.getValue());
        });
    }

    private long tokenNumForSymbol(@NonNull final String symbol) {
        final var normalized = symbol.toUpperCase(Locale.ROOT);
        if (SAUCERSWAP_POOL_BASE_SYMBOL.equals(normalized) || "WHBAR".equals(normalized)) {
            return SAUCERSWAP_WHBAR_TOKEN_ID;
        }
        if (PLEX_SYMBOL.equals(normalized)) {
            return PLEX_NUMBER;
        }
        var tokenNum = FIRST_TOKEN_NUM;
        for (final var seededSymbol : DEV_TOKEN_METADATA.keySet()) {
            if (seededSymbol.equals(normalized)) {
                return tokenNum;
            }
            tokenNum++;
        }
        throw new IllegalArgumentException("Unknown seeded token symbol: " + symbol);
    }

    private com.esaulpaugh.headlong.abi.Address longZeroAddressOf(final long entityNum) {
        return com.esaulpaugh.headlong.abi.Address.wrap(
                com.esaulpaugh.headlong.abi.Address.toChecksumAddress(new BigInteger(1, asEvmAddress(entityNum))));
    }

    private void setupPlexTopics(SystemContext systemContext) {
        for (int i = 0; i < NUM_TOPICS; i++) {
            final var op = ConsensusCreateTopicTransactionBody.newBuilder()
                    .autoRenewPeriod(new Duration(7776000L))
                    .build();
            systemContext.dispatchCreation(
                    b -> b.memo("Synthetic plex topic creation")
                            .consensusCreateTopic(op)
                            .build(),
                    FIRST_TOPIC_NUM + i);
        }
    }
}
