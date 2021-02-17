package com.hedera.services.usage.schedule;

/*
 * ‌
 * Hedera Services API Fees
 * ​
 * Copyright (C) 2018 - 2021 Hedera Hashgraph, LLC
 * ​
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ‍
 */

import com.google.protobuf.ByteString;
import com.hedera.services.test.IdUtils;
import com.hedera.services.usage.EstimatorFactory;
import com.hedera.services.usage.SigUsage;
import com.hedera.services.usage.TxnUsage;
import com.hedera.services.usage.TxnUsageEstimator;
import com.hederahashgraph.api.proto.java.ScheduleID;
import com.hederahashgraph.api.proto.java.ScheduleSignTransactionBody;
import com.hederahashgraph.api.proto.java.SignatureMap;
import com.hederahashgraph.api.proto.java.SignaturePair;
import com.hederahashgraph.api.proto.java.Timestamp;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.hedera.services.test.UsageUtils.A_USAGES_MATRIX;
import static com.hedera.services.usage.schedule.entities.ScheduleEntitySizes.SCHEDULE_ENTITY_SIZES;
import static com.hederahashgraph.fee.FeeBuilder.BASIC_ENTITY_ID_SIZE;
import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

public class ScheduleSignUsageTest {

	long now = 1_000L;
	long scheduledTXExpiry = 2_700;
	ScheduleID scheduleID = IdUtils.asSchedule("0.0.1");
	int numSigs = 3, sigSize = 100, numPayerKeys = 1;
	SigUsage sigUsage = new SigUsage(numSigs, sigSize, numPayerKeys);
	SignatureMap sigMap = SignatureMap.newBuilder()
			.addSigPair(
					SignaturePair.newBuilder()
							.setPubKeyPrefix(ByteString.copyFrom(new byte[]{0x01}))
							.setECDSA384(ByteString.copyFrom(new byte[]{0x01, 0x02}))
							.build()
			).build();

	ScheduleSignTransactionBody op;
	TransactionBody txn;

	EstimatorFactory factory;
	TxnUsageEstimator base;
	ScheduleSignUsage subject;

	@BeforeEach
	public void setUp() throws Exception {
		base = mock(TxnUsageEstimator.class);
		given(base.get()).willReturn(A_USAGES_MATRIX);

		factory = mock(EstimatorFactory.class);
		given(factory.get(any(), any(), any())).willReturn(base);

		TxnUsage.estimatorFactory = factory;
	}

	@Test
	public void createsExpectedDeltaForTXExpiry() {
		// setup:
		var expectedTxBytes = BASIC_ENTITY_ID_SIZE;
		givenBaseOp();

		// and:
		subject = ScheduleSignUsage.newEstimate(txn, sigUsage)
				.givenExpiry(scheduledTXExpiry);

		// when:
		var actual = subject.get();

		// then:
		assertEquals(A_USAGES_MATRIX, actual);
		// and:
		verify(base).addBpt(expectedTxBytes);
		verify(base).addRbs(0L);
		verify(base).addVpt(0);
	}

	@Test
	public void createsExpectedDeltaForSigMap() {
		// setup:
		var expectedTxBytes = BASIC_ENTITY_ID_SIZE + SCHEDULE_ENTITY_SIZES.bptScheduleReprGiven(sigMap);
		var expectedRamBytes = SCHEDULE_ENTITY_SIZES.sigBytesInScheduleReprGiven(sigMap);
		givenOpWithSigMap();

		// and:
		subject = ScheduleSignUsage.newEstimate(txn, sigUsage)
				.givenExpiry(scheduledTXExpiry);

		// when:
		var actual = subject.get();

		// then:
		assertEquals(A_USAGES_MATRIX, actual);
		// and:
		verify(base).addBpt(expectedTxBytes);
		verify(base).addRbs(expectedRamBytes * (scheduledTXExpiry - now));
		verify(base).addVpt(sigMap.getSigPairCount());
	}

	private void givenBaseOp() {
		op = ScheduleSignTransactionBody.newBuilder()
				.setScheduleID(scheduleID)
				.build();
		setTxn();
	}

	private void givenOpWithSigMap() {
		op = ScheduleSignTransactionBody.newBuilder()
				.setSigMap(sigMap)
				.build();
		setTxn();
	}

	private void setTxn() {
		txn = TransactionBody.newBuilder()
				.setTransactionID(TransactionID.newBuilder()
						.setTransactionValidStart(Timestamp.newBuilder()
								.setSeconds(now)))
				.setScheduleSign(op)
				.build();
	}
}
