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

package com.swirlds.regression.csv;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

import java.util.function.Supplier;

public class CsvParserV1 implements CsvParser {
	static final Logger log = LogManager.getLogger(CsvParserV1.class);
	static final Marker ERROR = MarkerManager.getMarker("EXCEPTION");

	private int emptyLineCounter = 0;

	public CsvStat[] getColumns(Supplier<String> lineSupplier) {
		CsvStat columns[] = null;
		while (true) {
			String line = lineSupplier.get();
			if (line.startsWith(",,")) {
				// line is now the categories
				String categories[] = line.substring(2).split(",");
				// next one is the column names
				line = lineSupplier.get();
				String names[] = line.substring(2).split(",");
				int colNum = categories.length;
				columns = new CsvStat[colNum];
				for (int i = 0; i < colNum; i++) {
					columns[i] = new CsvStat(categories[i], names[i]);
				}
				break;
			}
		}

		return columns;
	}

	@Override
	public boolean addNextData(Supplier<String> lineSupplier, CsvStat[] columns) {
		String line = lineSupplier.get();
		if (line == null) {
			return false;
		} else if (line.isEmpty() || line.length() < 2) {
			// Allow two empty lines in the csv file because when node kill reconnect test is performed,
			// it generates two empty lines after node restarts
			if (emptyLineCounter < 2) {
				emptyLineCounter++;
				return true;
			} else {
				return false;
			}
		}

		try {
			String data[] = line.substring(2).split(",");
			for (int i = 0; i < data.length; i++) {
				columns[i].addData(data[i]);
			}
		} catch (StringIndexOutOfBoundsException e) {
			log.error(ERROR, "Error when parsing the line '{}'.", line, e);
			return false;
		}

		return true;
	}

	@Override
	public void addAllData(Supplier<String> lineSupplier, CsvStat[] columns) {
		while (addNextData(lineSupplier, columns)) ;
		emptyLineCounter = 0;
	}
}