package com.hedera.services.legacy.core.jproto;

/*-
 * ‌
 * Hedera Services Node
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

import com.hedera.services.legacy.proto.utils.KeyExpansion;
import com.hedera.services.legacy.util.ComplexKeyManager;
import com.hedera.test.factories.scenarios.TxnHandlingScenario;
import com.hederahashgraph.api.proto.java.Key;
import org.apache.commons.codec.DecoderException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static com.hedera.services.utils.MiscUtils.asKeyUnchecked;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JKeyTest {
	@Test
	public void positiveConvertKeyTest() throws Exception {
		//given
		Key accountKey = ComplexKeyManager
				.genComplexKey(ComplexKeyManager.SUPPORTE_KEY_TYPES.single.name());

		//expect
		assertDoesNotThrow(() -> JKey.convertKey(accountKey, 1));
	}

	@Test
	public void negativeConvertKeyTest() throws Exception {
		//given
		Key accountKey = ComplexKeyManager
				.genComplexKey(ComplexKeyManager.SUPPORTE_KEY_TYPES.thresholdKey.name());

		//expect
		assertThrows(DecoderException.class, () -> JKey.convertKey(accountKey, KeyExpansion.KEY_EXPANSION_DEPTH + 1),
				"Exceeding max expansion depth of " + KeyExpansion.KEY_EXPANSION_DEPTH);
	}

	@Test
	public void rejectsEmptyKey() {
		// expect:
		assertThrows(DecoderException.class, () -> JKey.convertJKeyBasic(new JKey() {
			@Override
			public boolean isEmpty() {
				return false;
			}

			@Override
			public boolean isValid() {
				return false;
			}

			@Override
			public void setForScheduledTxn(boolean flag) { }

			@Override
			public boolean isForScheduledTxn() {
				return false;
			}
		}));
	}

	@Test
	void duplicatesAsExpected() {
		// given:
		var orig = TxnHandlingScenario.COMPLEX_KEY_ACCOUNT_KT.asJKeyUnchecked();

		// when:
		var dup = orig.duplicate();
		// then:
		assertNotSame(dup, orig);
		assertEquals(asKeyUnchecked(orig), asKeyUnchecked(dup));
	}
}
