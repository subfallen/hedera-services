package com.hedera.services.legacy.unit.handler;

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

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.TextFormat;
import com.hedera.services.fees.calculation.FeeCalcUtilsTest;
import com.hedera.services.legacy.unit.FCStorageWrapper;
import com.hedera.services.legacy.unit.GlobalFlag;
import com.hedera.services.legacy.unit.serialization.HFileMetaSerdeTest;
import com.hedera.services.utils.EntityIdUtils;
import com.hedera.services.utils.MiscUtils;
import com.hedera.services.state.submerkle.ExchangeRates;
import com.hederahashgraph.api.proto.java.AccountID;
import com.hederahashgraph.api.proto.java.ContractID;
import com.hederahashgraph.api.proto.java.CurrentAndNextFeeSchedule;
import com.hederahashgraph.api.proto.java.ExchangeRateSet;
import com.hederahashgraph.api.proto.java.FeeSchedule;
import com.hederahashgraph.api.proto.java.FileCreateTransactionBody;
import com.hederahashgraph.api.proto.java.FileGetInfoResponse.FileInfo;
import com.hederahashgraph.api.proto.java.FileID;
import com.hederahashgraph.api.proto.java.FileUpdateTransactionBody;
import com.hederahashgraph.api.proto.java.Key;
import com.hederahashgraph.api.proto.java.KeyList;
import com.hederahashgraph.api.proto.java.ResponseCodeEnum;
import com.hederahashgraph.api.proto.java.TransactionBody;
import com.hederahashgraph.api.proto.java.TransactionID;
import com.hederahashgraph.api.proto.java.TransactionReceipt;
import com.hederahashgraph.api.proto.java.TransactionRecord;
import com.hederahashgraph.builder.RequestBuilder;
import com.hedera.services.files.HFileMeta;
import com.hedera.services.legacy.core.jproto.JKey;
import com.hedera.services.legacy.unit.InvalidFileIDException;
import com.hedera.services.legacy.unit.InvalidFileWACLException;
import com.hedera.services.legacy.logic.ApplicationConstants;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.function.LongPredicate;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static com.hedera.services.utils.EntityIdUtils.readableId;

/**
 * Logic for file service used by the State class.
 *
 * @author hua
 */
public class FileServiceHandler {
  public static final LongPredicate IS_PROPERTIES_OR_PERMISSIONS = num -> (num == 121) || (num == 122);
  public static final long ADDRESS_ACC_NUM = 55L;
  public static final long FEE_ACC_NUM = 56L;
  public static final long EXCHANGE_ACC_NUM = 57L;
  private static final Logger log = LogManager.getLogger(FileServiceHandler.class);

  private GlobalFlag globalFlag;
  private FCStorageWrapper storageWrapper;
  private FeeScheduleInterceptor feeScheduleInterceptor;
  private ExchangeRates midnightRateSet;

  public FileServiceHandler(
          FCStorageWrapper storageWrapper,
          FeeScheduleInterceptor feeScheduleInterceptor,
          ExchangeRates exchangeRates
  ) {
    this.globalFlag = GlobalFlag.getInstance();
    this.storageWrapper = storageWrapper;
    this.midnightRateSet = exchangeRates;
    this.feeScheduleInterceptor = feeScheduleInterceptor;
  }

  /**
     * Converts a string to a byte array with UTF-8 encoding.
     *
     * @param str
     * 		string to be converted
     * @return converted byte array, or an empty array if there is an UnsupportedEncodingException.
     */
    public static byte[] string2bytesUTF8(String str) {
        byte[] rv = new byte[0];
        try {
            rv = str.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
        }
        return rv;
    }

	public static FileInfo lookupInfo(FileID fid, FCStorageWrapper fcfs) throws Exception {
		String metaPath = FeeCalcUtilsTest.pathOfMeta(fid);
		if (fcfs.fileExists(metaPath)) {
			long size = fcfs.getSize(FeeCalcUtilsTest.pathOf(fid));
			HFileMeta jInfo = HFileMeta.deserialize(fcfs.fileRead(metaPath));
			return HFileMetaSerdeTest.toGrpc(jInfo, fid, size);
		} else {
			throw new InvalidFileIDException(String.format("No such file '%s'!", readableId(fid)), fid);
		}
	}

  /**
   * Checks if an account has authority to update an entity based on the following rules: Treasury
   * account can update all entities below 0.0.1001 Account 0.0.50 can update all entities from
   * 0.0.50 - 0.0.80 Network Function Master Account A/c 0.0.50 - Update all Network Function
   * accounts & perform all the Network Functions listed below Network Function Accounts
   * A/c 0.0.55 - Update Address Book files (0.0.101/102) + Properties/Permission files (0.0.121/122)
   * A/c 0.0.56 - Update Fee schedule (0.0.111)
   * A/c 0.0.57 - Update Exchange Rate (0.0.112) + Properties/Permission files (0.0.121/122)
   *
   * @param account account to be checked
   * @param entity entity to be updated
   * @return true if the account has the authority to update the entity, false otherwise
   */
  public static boolean hasAuthorityToUpdate(AccountID account, Object entity) {
    if (isTreasury(account)) {
      return true;
    }

    boolean rv = false;
    long seq = getEntityNumber(entity);
    if (isMasterAccount(account)) {
      if((entity instanceof AccountID) &&
          isMasterAccount((AccountID) entity)) { // master account cannot update itself
        return false;
      } else if (seq >= 50 && seq <= 80) {
        rv = true;
      } else if(seq == 101 || seq == 102 || seq == 111 || seq == 112
    		  || seq == 121 || seq == 122 ) {
        rv = true;
      }
    } else if(account.equals(entity)) { // protected accounts can update themselves (excluding master account)
      return true;
    } else if (account.equals(genAccountID(ADDRESS_ACC_NUM))) {
      if (seq == 101 || seq == 102 || IS_PROPERTIES_OR_PERMISSIONS.test(seq)) {
        rv = true;
      }
    } else if (account.equals(genAccountID(FEE_ACC_NUM))) {
      if (seq == 111) {
        rv = true;
      }
    } else if (account.equals(genAccountID(EXCHANGE_ACC_NUM))) {
      if (seq == 112 || IS_PROPERTIES_OR_PERMISSIONS.test(seq)) {
        rv = true;
      }
    } else {
      //NoOp
    }

    return rv;
  }

  /**
   * Checks if the entity is protected, i.e. with ID number below 1000.
   *
   * @param entityID the ID of the entity
   * @return true if the entity should be protected, false otherwise
   */
  public static boolean isProtectedEntity(Object entityID) {
    long entityNum = -1l;
    long realmNum = -1l;
    long shardNum = -1l;
    if (entityID instanceof AccountID) {
      AccountID accID = (AccountID) entityID;
      entityNum = accID.getAccountNum();
      realmNum = accID.getRealmNum();
      shardNum = accID.getShardNum();
    } else if (entityID instanceof FileID) {
      FileID fileID = (FileID) entityID;
      entityNum = fileID.getFileNum();
      realmNum = fileID.getRealmNum();
      shardNum = fileID.getShardNum();
    } else {
      ContractID contratID = (ContractID) entityID;
      entityNum = contratID.getContractNum();
      realmNum = contratID.getRealmNum();
      shardNum = contratID.getShardNum();
    }

    boolean rv = (shardNum == 0) && (realmNum == 0) && (entityNum >= 1)
        && (entityNum <= 1000);
    return rv;
  }

  public static boolean isMasterAccount(AccountID account) {
    return (account.getShardNum() == 0 && account.getRealmNum() == 0 && account.getAccountNum() == 50);
  }

  public static boolean isTreasury(AccountID account) {
    return (account.getShardNum() == 0 && account.getRealmNum() == 0 && account.getAccountNum() == 2);
  }

  public static long getEntityNumber(Object entity) {
    long rv = -1l;

    if (entity instanceof AccountID) {
      rv = ((AccountID) entity).getAccountNum();
    } else if (entity instanceof FileID) {
      rv = ((FileID) entity).getFileNum();
    } else { // contract ID
      rv = ((ContractID) entity).getContractNum();
    }

    return rv;
  }

	public static AccountID genAccountID(long accNum) {
	  return AccountID.newBuilder().setShardNum(0).setRealmNum(0).setAccountNum(accNum).build();
	}

  public static JKey convertWacl(KeyList waclAsKeyList) throws InvalidFileWACLException {
        try {
            return JKey.mapKey(Key.newBuilder().setKeyList(waclAsKeyList).build());
        } catch (DecoderException e) {
            throw new InvalidFileWACLException("input wacl=" + waclAsKeyList, e);
        }
    }

  /**
   * Creates a file on the ledger.
   */
  public TransactionRecord createFile(TransactionBody gtx, Instant timestamp, FileID fid,
      final long selfId) {
    TransactionRecord txRecord;
    TransactionID txId = gtx.getTransactionID();
    Instant startTime = RequestBuilder.convertProtoTimeStamp(txId.getTransactionValidStart());
    FileCreateTransactionBody tx = gtx.getFileCreate();

    // get wacl and handle exception
    ResponseCodeEnum status = ResponseCodeEnum.SUCCESS;
    try {
      JKey jkey = convertWacl(tx.getKeys());

      // create virtual file for the data bytes
	  byte[] fileData = tx.getContents().toByteArray();
	  long fileSize = 0L;
	  if (fileData != null) {
	  	fileSize = fileData.length;
	  }
	  // compare size to allowable size
	  if (1024 * 1024L < fileSize) {
	  	throw new MaxFileSizeExceeded(
				String.format("The file size %d (bytes) is greater than allowed %d (bytes) ", fileSize,
	  					1024 * 1024L));
	  }
      String fileDataPath = FeeCalcUtilsTest.pathOf(fid);
      long expireTimeSec =
          RequestBuilder.convertProtoTimeStamp(tx.getExpirationTime()).getEpochSecond();

      if (log.isDebugEnabled()) {
        log.debug("Creating file at path :: " + fileDataPath + " :: nodeId = " + selfId);
      }
      storageWrapper
          .fileCreate(fileDataPath, fileData, startTime.getEpochSecond(), startTime.getNano(),
              expireTimeSec, string2bytesUTF8(fileDataPath));

      // create virtual file for the meta data
      HFileMeta fi = new HFileMeta(false, jkey, expireTimeSec);

      String fileMetaDataPath = FeeCalcUtilsTest.pathOfMeta(fid);
      storageWrapper.fileCreate(fileMetaDataPath, fi.serialize(), startTime.getEpochSecond(),
          startTime.getNano(), expireTimeSec, null);

    } catch (InvalidFileWACLException e) {
      if (log.isDebugEnabled()) {
        log.debug("File WACL invalid! tx=" + TextFormat.shortDebugString(tx), e);
      }
      status = ResponseCodeEnum.INVALID_FILE_WACL;
    } catch (IOException e) {
      if (log.isDebugEnabled()) {
        log.debug("File WACL serialization problem! tx=" + TextFormat.shortDebugString(tx), e);
      }
      status = ResponseCodeEnum.SERIALIZATION_FAILED;
    } catch (MaxFileSizeExceeded e) {
      status = ResponseCodeEnum.MAX_FILE_SIZE_EXCEEDED;
      log.debug("Maximum File Size Exceeded {}", ()->e);
	}

    TransactionReceipt receipt = RequestBuilder.getTransactionReceipt(fid, status,
        globalFlag.getExchangeRateSet());
    TransactionRecord.Builder txRecordBuilder = TransactionRecord.newBuilder().setReceipt(receipt)
        .setConsensusTimestamp(RequestBuilder.getTimestamp(timestamp)).setTransactionID(txId)
        .setMemo(gtx.getMemo()).setTransactionFee(gtx.getTransactionFee());

    txRecord = txRecordBuilder.build();
    if (log.isDebugEnabled()) {
      log.debug("createFile TransactionRecord creation in state: " + Instant.now() + "; txRecord="
          + TextFormat.shortDebugString(txRecord) + "; tx=" + TextFormat.shortDebugString(tx));
    }
    return txRecord;
  }


  private ResponseCodeEnum validateSystemFile(FileID fid, TransactionBody gtx, byte[] fileData,
      boolean appendFlag) {
    ResponseCodeEnum returnCode = ResponseCodeEnum.OK;

    // we're only concerned about system files, which are protected entities
    if (!isProtectedEntity(fid)) {
      return returnCode;
    }

    if (hasAuthorityToUpdate(gtx.getTransactionID().getAccountID(), fid)) {
      if (fid.getFileNum() == 111) {
        String fileDataPath = FeeCalcUtilsTest.pathOf(fid);
        byte[] fileContent;
        if (appendFlag) {
          byte[] existingContent = storageWrapper.fileRead(fileDataPath);
          fileContent = ArrayUtils.addAll(existingContent, fileData);
          returnCode = validateFeeData(fileContent);
        } else {
          returnCode = validateFeeData(fileData);
        }
        if (returnCode.equals(ResponseCodeEnum.OK)) {
        } else {
          log.warn("Failed to parse FeeSchedule file from Storage Wrapper .."
                  + "partial or corrupt file in Storage Wrapper : appendFlag={}", () -> appendFlag);
          returnCode = ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED;
        }
      } else if (fid.getFileNum() == ApplicationConstants.EXCHANGE_RATE_FILE_ACCOUNT_NUM) {
        returnCode = validateExchangeRateData(gtx.getTransactionID().getAccountID(), fileData);
      }
    } else {
      returnCode = ResponseCodeEnum.AUTHORIZATION_FAILED;
    }
    return returnCode;
  }

  private ResponseCodeEnum validateFeeData(byte[] fileData) {
    try {
      CurrentAndNextFeeSchedule parsedFile = CurrentAndNextFeeSchedule
          .parseFrom(fileData);
      FeeSchedule currentSchedule = parsedFile.getCurrentFeeSchedule();
      FeeSchedule nextSchedule = parsedFile.getNextFeeSchedule();
      if (currentSchedule == null || !currentSchedule.hasExpiryTime() || nextSchedule == null
          || !nextSchedule.hasExpiryTime()) {
        return ResponseCodeEnum.INVALID_FEE_FILE;
      }
    } catch (InvalidProtocolBufferException e) {
      //Not logging Exception Trace as it is expected
      log.error("Failed to parse FeeSchedule File ");
      return ResponseCodeEnum.INVALID_FEE_FILE;
    }
    return ResponseCodeEnum.OK;
  }

  private ResponseCodeEnum validateExchangeRateData(AccountID accountID, byte[] fileData) {
    try {
      ExchangeRateSet exchangeRateSet = ExchangeRateSet.parseFrom(fileData);
      if (exchangeRateSet.getCurrentRate().getHbarEquiv() <= 0 ||
          exchangeRateSet.getCurrentRate().getCentEquiv() <= 0 ||
          exchangeRateSet.getNextRate().getHbarEquiv() <= 0 ||
          exchangeRateSet.getNextRate().getCentEquiv() <= 0) {
        return ResponseCodeEnum.INVALID_EXCHANGE_RATE_FILE;
      }

      // If is not small change, if this Transaction is signed by Account 0.0.50(Master Account) or 0.0.2(Treasury), we should log a message and update Exchange Rate File; else we should log a error message and return ResponseCodeEnum.EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED
      if (!isSmallChange(exchangeRateSet)) {
        if (isMasterAccount(accountID) || isTreasury(accountID)) {
          log.info(
              "Exchange Rate File Update changes the Exchange Rate by a percentage greater than Exchange_Rate_Allowed_Percentage by {}",
              () -> EntityIdUtils.readableId(accountID));
        } else {
          log.error(
              "Invalid Exchange Rate File Update, because Account {} is not allowed to change the Exchange Rate by a percentage greater than Exchange_Rate_Allowed_Percentage",
              () -> EntityIdUtils.readableId(accountID));
          return ResponseCodeEnum.EXCHANGE_RATE_CHANGE_LIMIT_EXCEEDED;
        }
      }

    } catch (InvalidProtocolBufferException e) {
      log.error("Error in parsing exchange rate file..", e);
      return ResponseCodeEnum.INVALID_EXCHANGE_RATE_FILE;
    }
    return ResponseCodeEnum.OK;
  }

  /**
   * Updates a file.
   *
   * @param timestamp consensus timestamp
   */
  public TransactionRecord updateFile(TransactionBody gtx, Instant timestamp) {
    TransactionRecord txRecord;
    TransactionReceipt receipt;
    FileUpdateTransactionBody tx = gtx.getFileUpdate();
    FileID fid = tx.getFileID();
    try {
      String fileDataPath = FeeCalcUtilsTest.pathOf(fid);
      String fileMetaDataPath = FeeCalcUtilsTest.pathOfMeta(fid);
      HFileMeta fi = getMetaFileInfo(fid);
      if (fi.isDeleted()) {
        receipt = RequestBuilder.getTransactionReceipt(ResponseCodeEnum.FILE_DELETED,
            globalFlag.getExchangeRateSet());
      } else {
        if (tx.hasKeys()) {
          JKey wacl = convertWacl(tx.getKeys());
          fi.setWacl(wacl);
        }

        long expireTimeSec = fi.getExpiry();
        boolean expTimeUpdated = false;
        if (tx.hasExpirationTime()) {
          // new exp time ignored if not later than the current value
          long newExp = tx.getExpirationTime().getSeconds();
          if (newExp > expireTimeSec) {
            expTimeUpdated = true;
            expireTimeSec = newExp;
          }
        }
        ByteString fileData = tx.getContents();
        ResponseCodeEnum validateCode = ResponseCodeEnum.OK;
        if (fileData != null && !fileData.isEmpty()) {
          //check if allowed max size is not exceeded
  	      //compare size to allowable size
          long fileSize = 	fileData.toByteArray().length;
  	      if(1024*1024L < fileSize) {
  	        throw new MaxFileSizeExceeded(String.format("The file size %d (bytes) is greater than allowed %d (bytes) ",
                    fileSize,
                    1024*1024L));
  	      }
          validateCode = validateSystemFile(fid, gtx, fileData.toByteArray(), false);
          if (validateCode.equals(ResponseCodeEnum.OK) || validateCode
              .equals(ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED)) {
            storageWrapper.fileCreate(fileDataPath, fileData.toByteArray(),
                timestamp.getEpochSecond(), timestamp.getNano(), expireTimeSec,
                string2bytesUTF8(fileDataPath));
            if (validateCode.equals(ResponseCodeEnum.OK)
                && fid.getFileNum() == ApplicationConstants.EXCHANGE_RATE_FILE_ACCOUNT_NUM) {
            	ExchangeFileInterceptor exchangeFileInterceptor = new ExchangeFileInterceptor();
              exchangeFileInterceptor.update(storageWrapper, fid);

              // When MasterAccount updates file 0.0.112, it should also overwrite the ExchangeRateSet value which is saved in HGCAppState
              AccountID updater = gtx.getTransactionID().getAccountID();

              if (isMasterAccount(updater)) {
                log.info("Master account is updating the Exchange Rate file 0.0.112");
                ExchangeRateSet exchangeRateSet = readExchangeRateSetFromFile(storageWrapper);

                if (exchangeRateSet != null) {
                  midnightRateSet.replaceWith(exchangeRateSet);
                  log.info("Midnight exchange rate updated by {}", updater.toString());
                }
              }

            } else if (validateCode.equals(ResponseCodeEnum.OK) && fid.getFileNum() == 111) {
              feeScheduleInterceptor.update(storageWrapper, fid);
            } else if (validateCode.equals(ResponseCodeEnum.OK) && fid.getFileNum() == 121) {
            	ApplicationPropertiesInterceptor appPropertiesInterceptor = new ApplicationPropertiesInterceptor();
            	appPropertiesInterceptor.update(storageWrapper, fid);
             }else if (validateCode.equals(ResponseCodeEnum.OK)
                     && fid.getFileNum() == 122) {
             	APIPropertiesInterceptor apiPropertiesInterceptor = new APIPropertiesInterceptor();
             	apiPropertiesInterceptor.update(storageWrapper, fid);
              }
          }
        }

        // exp time may be updated independent of content change
        if (expTimeUpdated) {
          fi.setExpiry(expireTimeSec);
        }

        if (validateCode.equals(ResponseCodeEnum.OK) || validateCode
            .equals(ResponseCodeEnum.FEE_SCHEDULE_FILE_PART_UPLOADED)) {
          storageWrapper.fileCreate(fileMetaDataPath, fi.serialize(), timestamp.getEpochSecond(),
              timestamp.getNano(), expireTimeSec, null);
          validateCode = ResponseCodeEnum.SUCCESS;
        }
        receipt = RequestBuilder.getTransactionReceipt(validateCode,
            globalFlag.getExchangeRateSet());
      }
    } catch (IOException e) {
      receipt = RequestBuilder.getTransactionReceipt(ResponseCodeEnum.SERIALIZATION_FAILED,
          globalFlag.getExchangeRateSet());
      if (log.isDebugEnabled()) {
        log.debug("updateFile exception: can't serialize or deserialize! tx=" + tx, e);
      }
    } catch (InvalidFileWACLException e) {
      receipt = RequestBuilder.getTransactionReceipt(ResponseCodeEnum.INVALID_FILE_WACL,
          globalFlag.getExchangeRateSet());
      if (log.isDebugEnabled()) {
        log.debug("updateFile exception: invalid wacl! tx=" + tx, e);
      }

    } catch (InvalidFileIDException e) {
      receipt = RequestBuilder.getTransactionReceipt(ResponseCodeEnum.INVALID_FILE_ID,
          globalFlag.getExchangeRateSet());
      if (log.isDebugEnabled()) {
        log.debug("Problem getting info for file ID = " + fid, e);
      }
    } catch (MaxFileSizeExceeded e) {
      receipt = RequestBuilder.getTransactionReceipt(ResponseCodeEnum.MAX_FILE_SIZE_EXCEEDED,
  	        globalFlag.getExchangeRateSet());
  	  log.debug("Maximum File Size Exceeded {}", ()->e);
	}

    TransactionID txId = gtx.getTransactionID();
    txRecord = TransactionRecord.newBuilder().setReceipt(receipt)
        .setConsensusTimestamp(MiscUtils.asTimestamp(timestamp))
        .setTransactionID(txId).setMemo(gtx.getMemo()).setTransactionFee(gtx.getTransactionFee())
        .build();
    if (log.isDebugEnabled()) {
      log.debug("updateFile TransactionRecord creation in state: " + Instant.now() + "; txRecord="
          + TextFormat.shortDebugString(txRecord) + "; tx=" + TextFormat.shortDebugString(tx));
    }
    return txRecord;
  }

  public FileInfo getFileInfo(FileID fid) throws Exception {
  	return lookupInfo(fid, storageWrapper);
  }

  public FCStorageWrapper getStorageWrapper() {
    return storageWrapper;
  }


  /**
   * Gets meta data file info from storageWrapper given file ID.
   */
  public HFileMeta getMetaFileInfo(FileID fid) throws IOException, InvalidFileIDException {
    HFileMeta fileInfo;
    String fileMetaDataPath = FeeCalcUtilsTest.pathOfMeta(fid);
    if (storageWrapper.fileExists(fileMetaDataPath)) {
      byte[] oldBytes = storageWrapper.fileRead(fileMetaDataPath);
      fileInfo = HFileMeta.deserialize(oldBytes);
    } else {
      throw new InvalidFileIDException(String.format("Invalid FileID: %s", fid), fid);
    }

    return fileInfo;
  }

  /**
   * Read ExchangeRateSet from file
   * @return
   */
  public ExchangeRateSet readExchangeRateSetFromFile(FCStorageWrapper storageWrapper) {
    FileID fid = FileID.newBuilder().setFileNum(ApplicationConstants.EXCHANGE_RATE_FILE_ACCOUNT_NUM)
            .setRealmNum(ApplicationConstants.DEFAULT_FILE_REALM)
            .setShardNum(ApplicationConstants.DEFAULT_FILE_SHARD).build();
    String fileDataPath = FeeCalcUtilsTest.pathOf(fid);

    SystemFileCreation systemFileCreation = new SystemFileCreation(storageWrapper);
    return systemFileCreation.readExchangeRate(fileDataPath);
  }

  /**
   * Is the new exchange rate valid? The exchange rate of newC tiny cents per newH tinybars is valid
   * if it increases by no more than bound percent, nor decreases by more than the inverse amount.
   *
   * It is defined to be valid iff (for infinite-precision real numbers):
   * <pre>
   *    oldC/oldH * (1 + bound/100)
   * >= newC/newH
   * >= oldC/oldH * 1/(1 + bound/100)
   * </pre>
   *
   * Equivalently, it is valid iff both of the following are true:
   * <pre>
   * oldC * newH * (100 + bound) - newC * oldH * 100 >= 0
   * oldH * newC * (100 + bound) - newH * oldC * 100 >= 0
   * </pre>
   *
   * The expression above is for infinite-precision real numbers. This method actually performs the
   * computations in a way that completely avoids overflow and roundoff errors.
   *
   * All parameters much be positive. There are 100 million tinybars in an hbar, and 100 million
   * tinycents in a USD cent.
   *
   * @param bound max increase is by a factor of (1+bound/100), decrease by 1 over that
   * @param oldC the old exchange rate is for this many tinycents
   * @param oldH the old exchange rate is for this many tinybars
   * @param newC the new exchange rate is for this many tinycents
   * @param newH the new exchange rate is for this many tinybars
   */
  public boolean isSmallChange(long bound, long oldC, long oldH, long newC, long newH) {
    BigInteger k100 = BigInteger.valueOf(100);
    BigInteger b100 = BigInteger.valueOf(bound).add(k100);
    BigInteger oC = BigInteger.valueOf(oldC);
    BigInteger oH = BigInteger.valueOf(oldH);
    BigInteger nC = BigInteger.valueOf(newC);
    BigInteger nH = BigInteger.valueOf(newH);

    return
        bound > 0 && oldC > 0 && oldH > 0 && newC > 0 && newH > 0
            && oC.multiply(nH).multiply(b100).subtract(
            nC.multiply(oH).multiply(k100)
        ).signum() >= 0
            && oH.multiply(nC).multiply(b100).subtract(
            nH.multiply(oC).multiply(k100)
        ).signum() >= 0;
  }

  /**
   * Return true only when there is small change in both currentRate and nextRate
   */
  public boolean isSmallChange(ExchangeRateSet exchangeRateSet) {
    return isSmallChange(5,
        midnightRateSet.getCurrCentEquiv(), midnightRateSet.getCurrHbarEquiv(),
        exchangeRateSet.getCurrentRate().getCentEquiv(),
        exchangeRateSet.getCurrentRate().getHbarEquiv()) && isSmallChange(
        5,
        midnightRateSet.getNextCentEquiv(), midnightRateSet.getNextHbarEquiv(),
        exchangeRateSet.getNextRate().getCentEquiv(), exchangeRateSet.getNextRate().getHbarEquiv());
  }
}
