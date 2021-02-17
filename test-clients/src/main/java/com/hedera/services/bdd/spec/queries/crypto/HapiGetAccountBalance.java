package com.hedera.services.bdd.spec.queries.crypto;

/*-
 * ‌
 * Hedera Services Test Clients
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.common.base.MoreObjects;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.CryptoGetAccountBalanceQuery;
import com.hederahashgraph.api.proto.java.HederaFunctionality;
import com.hederahashgraph.api.proto.java.Query;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TokenBalance;
import com.hederahashgraph.api.proto.java.TokenID;
import com.hederahashgraph.api.proto.java.Transaction;
import com.hedera.services.bdd.spec.HapiApiSpec;
import com.hedera.services.bdd.spec.HapiPropertySource;
import com.hedera.services.bdd.spec.queries.HapiQueryOp;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.hedera.services.bdd.spec.queries.QueryUtils.answerCostHeader;
import static com.hedera.services.bdd.spec.queries.QueryUtils.answerHeader;
import static com.hedera.services.bdd.spec.transactions.TxnUtils.asTokenId;

public class HapiGetAccountBalance extends HapiQueryOp<HapiGetAccountBalance> {
	private static final Logger log = LogManager.getLogger(HapiGetAccountBalance.class);

	private Pattern DOT_DELIMTED_ACCOUNT = Pattern.compile("\\d+[.]\\d+[.]\\d+");
	private String entity;
	Optional<Long> expected = Optional.empty();
	Optional<Supplier<String>> entityFn = Optional.empty();
	Optional<Function<HapiApiSpec, Function<Long, Optional<String>>>> expectedCondition = Optional.empty();

	List<Map.Entry<String, String>> expectedTokenBalances = Collections.EMPTY_LIST;

	public HapiGetAccountBalance(String entity) {
		this.entity = entity;
	}

	public HapiGetAccountBalance(Supplier<String> supplier) {
		this.entityFn = Optional.of(supplier);
	}

	public HapiGetAccountBalance hasTinyBars(long amount) {
		expected = Optional.of(amount);
		return this;
	}
	public HapiGetAccountBalance hasTinyBars(Function<HapiApiSpec, Function<Long, Optional<String>>> condition) {
		expectedCondition = Optional.of(condition);
		return this;
	}
	public HapiGetAccountBalance hasTokenBalance(String token, long amount) {
		if (expectedTokenBalances.isEmpty()) {
			expectedTokenBalances = new ArrayList<>();
		}
		expectedTokenBalances.add(new AbstractMap.SimpleImmutableEntry<>(token, amount + "-G"));
		return this;
	}
	public HapiGetAccountBalance hasTokenBalance(String token, long amount, int decimals) {
		if (expectedTokenBalances.isEmpty()) {
			expectedTokenBalances = new ArrayList<>();
		}
		expectedTokenBalances.add(new AbstractMap.SimpleImmutableEntry<>(token, amount + "-" + decimals));
		return this;
	}

	@Override
	public HederaFunctionality type() {
		return HederaFunctionality.CryptoGetAccountBalance;
	}

	@Override
	protected HapiGetAccountBalance self() {
		return this;
	}

	@Override
	protected void assertExpectationsGiven(HapiApiSpec spec) throws Throwable {
		long actual = response.getCryptogetAccountBalance().getBalance();
		if (verboseLoggingOn) {
			log.info("Explicit token balances: " + response.getCryptogetAccountBalance().getTokenBalancesList());
		}
		if (expectedCondition.isPresent()) {
			Function<Long, Optional<String>> condition = expectedCondition.get().apply(spec);
			Optional<String> failure = condition.apply(actual);
			if (failure.isPresent()) {
				Assert.fail("Bad balance! :: " + failure.get());
			}
		} else if (expected.isPresent()) {
			Assert.assertEquals("Wrong balance!", expected.get().longValue(), actual);
		}

		if (expectedTokenBalances.size() > 0) {
			Pair<Long, Integer> defaultTb = Pair.of(0L, 0);
			Map<TokenID, Pair<Long, Integer>> actualTokenBalances = response.getCryptogetAccountBalance().getTokenBalancesList()
					.stream()
					.collect(Collectors.toMap(
							TokenBalance::getTokenId,
							tb -> Pair.of(tb.getBalance(), tb.getDecimals())));
			for (Map.Entry<String, String> tokenBalance : expectedTokenBalances) {
				var tokenId = asTokenId(tokenBalance.getKey(), spec);
				String[] expectedParts = tokenBalance.getValue().split("-");
				Long expectedBalance = Long.valueOf(expectedParts[0]);
				Assert.assertEquals(String.format(
						"Wrong balance for token '%s'!", HapiPropertySource.asTokenString(tokenId)),
						expectedBalance,
						actualTokenBalances.getOrDefault(tokenId, defaultTb).getLeft());
				if (!"G".equals(expectedParts[1])) {
					Integer expectedDecimals = Integer.valueOf(expectedParts[1]);
					Assert.assertEquals(String.format(
							"Wrong decimals for token '%s'!", HapiPropertySource.asTokenString(tokenId)),
							expectedDecimals,
							actualTokenBalances.getOrDefault(tokenId, defaultTb).getRight());
				}
			}
		}
	}

	@Override
	protected void submitWith(HapiApiSpec spec, Transaction payment) throws Throwable {
		Query query = getAccountBalanceQuery(spec, payment, false);
		response = spec.clients().getCryptoSvcStub(targetNodeFor(spec), useTls).cryptoGetBalance(query);
		ResponseCodeEnum status = response.getCryptogetAccountBalance().getHeader().getNodeTransactionPrecheckCode();
		if (status == ResponseCodeEnum.ACCOUNT_DELETED) {
			log.info(spec.logPrefix() + entity + " was actually deleted!");
		} else {
			long balance = response.getCryptogetAccountBalance().getBalance();
			long TINYBARS_PER_HBAR = 100_000_000L;
			long hBars = balance / TINYBARS_PER_HBAR;
			if (!loggingOff) {
				log.info(spec.logPrefix() + "balance for '" + entity + "': " + balance + " tinyBars (" + hBars + "ħ)");
			}
		}
	}

	private Query getAccountBalanceQuery(HapiApiSpec spec, Transaction payment, boolean costOnly) {
		if (entityFn.isPresent()) {
			entity = entityFn.get().get();
		}
		Consumer<CryptoGetAccountBalanceQuery.Builder> config;
		if (spec.registry().hasContractId(entity)) {
			config = b -> b.setContractID(spec.registry().getContractId(entity));
		} else {
			Matcher m = DOT_DELIMTED_ACCOUNT.matcher(entity);
			AccountID id = m.matches() ? HapiPropertySource.asAccount(entity) : spec.registry().getAccountID(entity);
			config = b -> b.setAccountID(id);
		}
		CryptoGetAccountBalanceQuery.Builder query = CryptoGetAccountBalanceQuery.newBuilder()
				.setHeader(costOnly ? answerCostHeader(payment) : answerHeader(payment));
		config.accept(query);
		return Query.newBuilder().setCryptogetAccountBalance(query).build();
	}

	@Override
	protected boolean needsPayment() {
		return false;
	}

	@Override
	protected MoreObjects.ToStringHelper toStringHelper() {
		return super.toStringHelper().add("account", entity);
	}
}
