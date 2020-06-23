/*
 * (c) 2016-2020 Swirlds, Inc.
 *
 * This software is the confidential and proprietary information of
 * Swirlds, Inc. ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Swirlds.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

package com.swirlds.regression.experiment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExperimentSummaryStorageTest {

	@Test
	void storeReadCompareDelete() throws IOException {
		ExperimentSummaryData test1run1 = new ExperimentSummaryData(
				false,
				false,
				false,
				"test1",
				"1"
		);
		ExperimentSummaryData test1_1run1 = new ExperimentSummaryData(
				false,
				false,
				false,
				"test1.1",
				"11"
		);
		ExperimentSummaryData test1run2 = new ExperimentSummaryData(
				true,
				false,
				false,
				"test1",
				"2"
		);
		ExperimentSummaryData test2run1 = new ExperimentSummaryData(
				true,
				true,
				true,
				"test2",
				"3"
		);

		ExperimentSummaryStorage.storeSummary(test1run1, new Date(123456));
		ExperimentSummaryStorage.storeSummary(test1_1run1, new Date());
		ExperimentSummaryStorage.storeSummary(test1run2, new Date());
		ExperimentSummaryStorage.storeSummary(test2run1, new Date());

		List<ExperimentSummary> list1 = ExperimentSummaryStorage.readSummaries("test1", 10);
		assertEquals(test1run1, list1.get(0));
		assertEquals(test1run2, list1.get(1));

		List<ExperimentSummary> list1_1 = ExperimentSummaryStorage.readSummaries("test1.1", 10);
		assertEquals(test1_1run1, list1_1.get(0));

		List<ExperimentSummary> list2 = ExperimentSummaryStorage.readSummaries("test2", 10);
		assertEquals(test2run1, list2.get(0));

		ExperimentSummaryStorage.deleteOldSummaries("test1", 0);
		ExperimentSummaryStorage.deleteOldSummaries("test1.1", 0);
		ExperimentSummaryStorage.deleteOldSummaries("test2", 0);

		list1 = ExperimentSummaryStorage.readSummaries("test1", 10);
		assertEquals(0, list1.size());
		list1_1 = ExperimentSummaryStorage.readSummaries("test1_1", 10);
		assertEquals(0, list1_1.size());
		list2 = ExperimentSummaryStorage.readSummaries("test2", 10);
		assertEquals(0, list2.size());
	}

	@Test
	void testGetDeleteOrder() throws IOException {
		String testName = "testDelete";
		int testNumber = 20;

		List<ExperimentSummaryData> list = new LinkedList<>();
		for (int i = 0; i < testNumber; i++) {
			ExperimentSummaryData data = new ExperimentSummaryData();
			data.setName(testName);
			data.setUniqueId(Integer.toString(i));
			list.add(data);
			ExperimentSummaryStorage.storeSummary(data, new Date(i));
		}
		int latestNumber = testNumber / 2;
		List<ExperimentSummary> loaded = ExperimentSummaryStorage.readSummaries(testName, latestNumber);
		assertEquals(latestNumber, loaded.size());
		for (int i = 0; i < latestNumber; i++) {
			assertEquals(list.get(latestNumber + i), loaded.get(i));
		}

		ExperimentSummaryStorage.deleteOldSummaries(testName, latestNumber);
		loaded = ExperimentSummaryStorage.readSummaries(testName, latestNumber);
		assertEquals(latestNumber, loaded.size());
		for (int i = 0; i < latestNumber; i++) {
			assertEquals(list.get(latestNumber + i), loaded.get(i));
		}

		ExperimentSummaryStorage.deleteOldSummaries(testName, 0);
		loaded = ExperimentSummaryStorage.readSummaries(testName, latestNumber);
		assertEquals(0, loaded.size());
	}
}