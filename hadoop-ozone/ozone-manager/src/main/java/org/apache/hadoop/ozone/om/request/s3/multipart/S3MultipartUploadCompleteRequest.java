/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.request.s3.multipart;

import com.google.common.base.Optional;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.hadoop.hdds.client.ReplicationConfig;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.utils.db.cache.CacheKey;
import org.apache.hadoop.hdds.utils.db.cache.CacheValue;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.audit.OMAction;
import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.OzoneManager;
import org.apache.hadoop.ozone.om.exceptions.OMException;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfo;
import org.apache.hadoop.ozone.om.helpers.OmKeyLocationInfoGroup;
import org.apache.hadoop.ozone.om.helpers.OmMultipartKeyInfo;
import org.apache.hadoop.ozone.om.ratis.utils.OzoneManagerDoubleBufferHelper;
import org.apache.hadoop.ozone.om.request.key.OMKeyRequest;
import org.apache.hadoop.ozone.om.request.util.OmResponseUtil;
import org.apache.hadoop.ozone.om.response.OMClientResponse;
import org.apache.hadoop.ozone.om.response.s3.multipart.S3MultipartUploadCompleteResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.KeyArgs;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.MultipartUploadCompleteRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.MultipartUploadCompleteResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMRequest;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.OMResponse;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos.PartKeyInfo;
import org.apache.hadoop.ozone.security.acl.IAccessAuthorizer;
import org.apache.hadoop.ozone.security.acl.OzoneObj;
import org.apache.hadoop.util.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.hadoop.ozone.om.exceptions.OMException.ResultCodes.NOT_A_FILE;
import static org.apache.hadoop.ozone.om.lock.OzoneManagerLock.Resource.BUCKET_LOCK;

/**
 * Handle Multipart upload complete request.
 */
public class S3MultipartUploadCompleteRequest extends OMKeyRequest {

  private static final Logger LOG =
      LoggerFactory.getLogger(S3MultipartUploadCompleteRequest.class);

  public S3MultipartUploadCompleteRequest(OMRequest omRequest,
      BucketLayout bucketLayout) {
    super(omRequest, bucketLayout);
  }

  @Override
  public OMRequest preExecute(OzoneManager ozoneManager) throws IOException {
    MultipartUploadCompleteRequest multipartUploadCompleteRequest =
        getOmRequest().getCompleteMultiPartUploadRequest();

    KeyArgs keyArgs = multipartUploadCompleteRequest.getKeyArgs();
    String keyPath = keyArgs.getKeyName();
    keyPath = validateAndNormalizeKey(ozoneManager.getEnableFileSystemPaths(),
        keyPath, getBucketLayout());

    return getOmRequest().toBuilder().setCompleteMultiPartUploadRequest(
        multipartUploadCompleteRequest.toBuilder().setKeyArgs(
            keyArgs.toBuilder().setModificationTime(Time.now())
                .setKeyName(keyPath))).setUserInfo(getUserInfo()).build();
  }

  @Override
  @SuppressWarnings("methodlength")
  public OMClientResponse validateAndUpdateCache(OzoneManager ozoneManager,
      long trxnLogIndex, OzoneManagerDoubleBufferHelper omDoubleBufferHelper) {
    MultipartUploadCompleteRequest multipartUploadCompleteRequest =
        getOmRequest().getCompleteMultiPartUploadRequest();

    KeyArgs keyArgs = multipartUploadCompleteRequest.getKeyArgs();

    List<OzoneManagerProtocolProtos.Part> partsList =
        multipartUploadCompleteRequest.getPartsListList();
    Map<String, String> auditMap = buildKeyArgsAuditMap(keyArgs);

    String volumeName = keyArgs.getVolumeName();
    String bucketName = keyArgs.getBucketName();
    final String requestedVolume = volumeName;
    final String requestedBucket = bucketName;
    String keyName = keyArgs.getKeyName();
    String uploadID = keyArgs.getMultipartUploadID();
    String multipartKey = null;

    ozoneManager.getMetrics().incNumCompleteMultipartUploads();

    OMMetadataManager omMetadataManager = ozoneManager.getMetadataManager();

    boolean acquiredLock = false;
    OMResponse.Builder omResponse = OmResponseUtil.getOMResponseBuilder(
        getOmRequest());
    OMClientResponse omClientResponse = null;
    IOException exception = null;
    Result result = null;
    try {
      keyArgs = resolveBucketLink(ozoneManager, keyArgs, auditMap);
      volumeName = keyArgs.getVolumeName();
      bucketName = keyArgs.getBucketName();

      multipartKey = omMetadataManager.getMultipartKey(volumeName,
          bucketName, keyName, uploadID);

      // check Acl
      checkKeyAcls(ozoneManager, volumeName, bucketName, keyName,
          IAccessAuthorizer.ACLType.WRITE, OzoneObj.ResourceType.KEY);

      acquiredLock = omMetadataManager.getLock().acquireWriteLock(BUCKET_LOCK,
          volumeName, bucketName);

      validateBucketAndVolume(omMetadataManager, volumeName, bucketName);

      String ozoneKey = omMetadataManager.getOzoneKey(
          volumeName, bucketName, keyName);

      String dbOzoneKey =
          getDBOzoneKey(omMetadataManager, volumeName, bucketName, keyName);

      String dbMultipartOpenKey =
          getDBMultipartOpenKey(volumeName, bucketName, keyName, uploadID,
              omMetadataManager);

      OmMultipartKeyInfo multipartKeyInfo = omMetadataManager
          .getMultipartInfoTable().get(multipartKey);

      // Check for directory exists with same name, if it exists throw error. 
      checkDirectoryAlreadyExists(ozoneManager, volumeName, bucketName, keyName,
          omMetadataManager);

      if (multipartKeyInfo == null) {
        throw new OMException(
            failureMessage(requestedVolume, requestedBucket, keyName),
            OMException.ResultCodes.NO_SUCH_MULTIPART_UPLOAD_ERROR);
      }
      TreeMap<Integer, PartKeyInfo> partKeyInfoMap =
          multipartKeyInfo.getPartKeyInfoMap();

      if (partsList.size() > 0) {
        if (partKeyInfoMap.size() == 0) {
          LOG.error("Complete MultipartUpload failed for key {} , MPU Key has" +
                  " no parts in OM, parts given to upload are {}", ozoneKey,
              partsList);
          throw new OMException(
              failureMessage(requestedVolume, requestedBucket, keyName),
              OMException.ResultCodes.INVALID_PART);
        }

        // First Check for Invalid Part Order.
        List< Integer > partNumbers = new ArrayList<>();
        int partsListSize = getPartsListSize(requestedVolume,
                requestedBucket, keyName, ozoneKey, partNumbers, partsList);

        List<OmKeyLocationInfo> partLocationInfos = new ArrayList<>();
        long dataSize = getMultipartDataSize(requestedVolume, requestedBucket,
                keyName, ozoneKey, partKeyInfoMap, partsListSize,
                partLocationInfos, partsList, ozoneManager);

        // All parts have same replication information. Here getting from last
        // part.
        OmKeyInfo omKeyInfo =
            getOmKeyInfo(ozoneManager, trxnLogIndex, keyArgs, volumeName,
                bucketName, keyName, dbMultipartOpenKey, omMetadataManager,
                dbOzoneKey, partKeyInfoMap, partLocationInfos, dataSize);

        //Find all unused parts.
        List<OmKeyInfo> unUsedParts = new ArrayList<>();
        for (Map.Entry< Integer, PartKeyInfo> partKeyInfo :
            partKeyInfoMap.entrySet()) {
          if (!partNumbers.contains(partKeyInfo.getKey())) {
            unUsedParts.add(OmKeyInfo
                .getFromProtobuf(partKeyInfo.getValue().getPartKeyInfo()));
          }
        }

        updateCache(omMetadataManager, dbOzoneKey, dbMultipartOpenKey,
            multipartKey, omKeyInfo, trxnLogIndex);

        omResponse.setCompleteMultiPartUploadResponse(
            MultipartUploadCompleteResponse.newBuilder()
                .setVolume(requestedVolume)
                .setBucket(requestedBucket)
                .setKey(keyName)
                .setHash(DigestUtils.sha256Hex(keyName)));

        omClientResponse =
            getOmClientResponse(multipartKey, omResponse, dbMultipartOpenKey,
                omKeyInfo, unUsedParts);

        result = Result.SUCCESS;
      } else {
        throw new OMException(
            failureMessage(requestedVolume, requestedBucket, keyName) +
            " because of empty part list",
            OMException.ResultCodes.INVALID_REQUEST);
      }

    } catch (IOException ex) {
      result = Result.FAILURE;
      exception = ex;
      omClientResponse = getOmClientResponse(omResponse, exception);
    } finally {
      addResponseToDoubleBuffer(trxnLogIndex, omClientResponse,
          omDoubleBufferHelper);
      if (acquiredLock) {
        omMetadataManager.getLock().releaseWriteLock(BUCKET_LOCK,
            volumeName, bucketName);
      }
    }

    logResult(ozoneManager, multipartUploadCompleteRequest, partsList,
            auditMap, volumeName, bucketName, keyName, exception, result);

    return omClientResponse;
  }

  protected S3MultipartUploadCompleteResponse getOmClientResponse(
      OMResponse.Builder omResponse, IOException exception) {
    return new S3MultipartUploadCompleteResponse(
        createErrorOMResponse(omResponse, exception));
  }

  protected OMClientResponse getOmClientResponse(String multipartKey,
      OMResponse.Builder omResponse, String dbMultipartOpenKey,
      OmKeyInfo omKeyInfo, List<OmKeyInfo> unUsedParts) {

    return new S3MultipartUploadCompleteResponse(omResponse.build(),
        multipartKey, dbMultipartOpenKey, omKeyInfo, unUsedParts);
  }

  protected void checkDirectoryAlreadyExists(OzoneManager ozoneManager,
      String volumeName, String bucketName, String keyName,
      OMMetadataManager omMetadataManager) throws IOException {
    if (ozoneManager.getEnableFileSystemPaths()) {
      if (checkDirectoryAlreadyExists(volumeName, bucketName, keyName,
              omMetadataManager)) {
        throw new OMException("Can not Complete MPU for file: " + keyName +
                " as there is already directory in the given path",
                NOT_A_FILE);
      }
    }
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  protected void logResult(OzoneManager ozoneManager,
      MultipartUploadCompleteRequest multipartUploadCompleteRequest,
      List<OzoneManagerProtocolProtos.Part> partsList,
      Map<String, String> auditMap, String volumeName,
      String bucketName, String keyName, IOException exception,
      Result result) {
    auditMap.put(OzoneConsts.MULTIPART_LIST, partsList.toString()
        .replaceAll("\\n", " "));

    // audit log
    auditLog(ozoneManager.getAuditLogger(), buildAuditMessage(
        OMAction.COMPLETE_MULTIPART_UPLOAD, auditMap, exception,
        getOmRequest().getUserInfo()));

    switch (result) {
    case SUCCESS:
      LOG.debug("MultipartUpload Complete request is successful for Key: {} " +
          "in Volume/Bucket {}/{}", keyName, volumeName, bucketName);
      break;
    case FAILURE:
      ozoneManager.getMetrics().incNumCompleteMultipartUploadFails();
      LOG.error("MultipartUpload Complete request failed for Key: {} " +
          "in Volume/Bucket {}/{}", keyName, volumeName, bucketName,
          exception);
      break;
    default:
      LOG.error("Unrecognized Result for S3MultipartUploadCommitRequest: {}",
          multipartUploadCompleteRequest);
    }
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  protected OmKeyInfo getOmKeyInfo(OzoneManager ozoneManager, long trxnLogIndex,
      KeyArgs keyArgs, String volumeName, String bucketName, String keyName,
      String multipartOpenKey, OMMetadataManager omMetadataManager,
      String ozoneKey, TreeMap<Integer, PartKeyInfo> partKeyInfoMap,
      List<OmKeyLocationInfo> partLocationInfos, long dataSize)
          throws IOException {
    HddsProtos.ReplicationType type = partKeyInfoMap.lastEntry().getValue()
        .getPartKeyInfo().getType();
    HddsProtos.ReplicationFactor factor =
        partKeyInfoMap.lastEntry().getValue().getPartKeyInfo().getFactor();

    OmKeyInfo omKeyInfo = getOmKeyInfoFromKeyTable(ozoneKey, keyName,
            omMetadataManager);
    if (omKeyInfo == null) {
      // This is a newly added key, it does not have any versions.
      OmKeyLocationInfoGroup keyLocationInfoGroup = new
          OmKeyLocationInfoGroup(0, partLocationInfos, true);

      // Get the objectID of the key from OpenKeyTable
      OmKeyInfo dbOpenKeyInfo = getOmKeyInfoFromOpenKeyTable(multipartOpenKey,
              keyName, omMetadataManager);

      // A newly created key, this is the first version.
      OmKeyInfo.Builder builder =
          new OmKeyInfo.Builder().setVolumeName(volumeName)
          .setBucketName(bucketName).setKeyName(dbOpenKeyInfo.getKeyName())
          .setReplicationConfig(
              ReplicationConfig.fromTypeAndFactor(type, factor))
          .setCreationTime(keyArgs.getModificationTime())
          .setModificationTime(keyArgs.getModificationTime())
          .setDataSize(dataSize)
          .setFileEncryptionInfo(dbOpenKeyInfo.getFileEncryptionInfo())
          .setOmKeyLocationInfos(
              Collections.singletonList(keyLocationInfoGroup))
          .setAcls(dbOpenKeyInfo.getAcls());
      // Check if db entry has ObjectID. This check is required because
      // it is possible that between multipart key uploads and complete,
      // we had an upgrade.
      if (dbOpenKeyInfo.getObjectID() != 0) {
        builder.setObjectID(dbOpenKeyInfo.getObjectID());
      }
      updatePrefixFSOInfo(dbOpenKeyInfo, builder);
      omKeyInfo = builder.build();
    } else {
      // Already a version exists, so we should add it as a new version.
      // But now as versioning is not supported, just following the commit
      // key approach. When versioning support comes, then we can uncomment
      // below code keyInfo.addNewVersion(locations);
      omKeyInfo.updateLocationInfoList(partLocationInfos, true, true);
      omKeyInfo.setModificationTime(keyArgs.getModificationTime());
      omKeyInfo.setDataSize(dataSize);
    }
    omKeyInfo.setUpdateID(trxnLogIndex, ozoneManager.isRatisEnabled());
    return omKeyInfo;
  }

  protected void updatePrefixFSOInfo(OmKeyInfo dbOpenKeyInfo,
      OmKeyInfo.Builder builder) {
    // FSO is disabled. Do nothing.
  }

  protected String getDBOzoneKey(OMMetadataManager omMetadataManager,
      String volumeName, String bucketName, String keyName) throws IOException {
    return omMetadataManager.getOzoneKey(volumeName, bucketName, keyName);
  }

  protected String getDBMultipartOpenKey(String volumeName, String bucketName,
      String keyName, String uploadID, OMMetadataManager omMetadataManager)
      throws IOException {
    return omMetadataManager
        .getMultipartKey(volumeName, bucketName, keyName, uploadID);
  }

  protected OmKeyInfo getOmKeyInfoFromKeyTable(String dbOzoneKey,
      String keyName, OMMetadataManager omMetadataManager) throws IOException {
    return omMetadataManager.getKeyTable(getBucketLayout()).get(dbOzoneKey);
  }

  protected OmKeyInfo getOmKeyInfoFromOpenKeyTable(String dbMultipartKey,
      String keyName, OMMetadataManager omMetadataManager) throws IOException {
    return omMetadataManager.getOpenKeyTable(getBucketLayout())
        .get(dbMultipartKey);
  }

  protected void addKeyTableCacheEntry(OMMetadataManager omMetadataManager,
      String dbOzoneKey, OmKeyInfo omKeyInfo, long transactionLogIndex) {

    // Add key entry to file table.
    omMetadataManager.getKeyTable(getBucketLayout())
        .addCacheEntry(new CacheKey<>(dbOzoneKey),
            new CacheValue<>(Optional.of(omKeyInfo), transactionLogIndex));
  }

  private int getPartsListSize(String requestedVolume,
      String requestedBucket, String keyName, String ozoneKey,
      List<Integer> partNumbers,
      List<OzoneManagerProtocolProtos.Part> partsList) throws OMException {
    int prevPartNumber = partsList.get(0).getPartNumber();
    int partsListSize = partsList.size();
    partNumbers.add(prevPartNumber);
    for (int i = 1; i < partsListSize; i++) {
      int currentPartNumber = partsList.get(i).getPartNumber();
      if (prevPartNumber >= currentPartNumber) {
        LOG.error("PartNumber at index {} is {}, and its previous " +
                "partNumber at index {} is {} for ozonekey is " +
                "{}", i, currentPartNumber, i - 1, prevPartNumber,
            ozoneKey);
        throw new OMException(
            failureMessage(requestedVolume, requestedBucket, keyName) +
            " because parts are in Invalid order.",
            OMException.ResultCodes.INVALID_PART_ORDER);
      }
      prevPartNumber = currentPartNumber;
      partNumbers.add(prevPartNumber);
    }
    return partsListSize;
  }

  @SuppressWarnings("checkstyle:ParameterNumber")
  private long getMultipartDataSize(String requestedVolume,
      String requestedBucket, String keyName, String ozoneKey,
      TreeMap<Integer, PartKeyInfo> partKeyInfoMap,
      int partsListSize, List<OmKeyLocationInfo> partLocationInfos,
      List<OzoneManagerProtocolProtos.Part> partsList,
      OzoneManager ozoneManager) throws OMException {
    long dataSize = 0;
    int currentPartCount = 0;
    // Now do actual logic, and check for any Invalid part during this.
    for (OzoneManagerProtocolProtos.Part part : partsList) {
      currentPartCount++;
      int partNumber = part.getPartNumber();
      String partName = part.getPartName();

      PartKeyInfo partKeyInfo = partKeyInfoMap.get(partNumber);

      String dbPartName = null;
      if (partKeyInfo != null) {
        dbPartName = partKeyInfo.getPartName();
      }
      if (!StringUtils.equals(partName, dbPartName)) {
        String omPartName = partKeyInfo == null ? null : dbPartName;
        throw new OMException(
            failureMessage(requestedVolume, requestedBucket, keyName) +
            ". Provided Part info is { " + partName + ", " + partNumber +
            "}, whereas OM has partName " + omPartName,
            OMException.ResultCodes.INVALID_PART);
      }

      OmKeyInfo currentPartKeyInfo = OmKeyInfo
          .getFromProtobuf(partKeyInfo.getPartKeyInfo());

      // Except for last part all parts should have minimum size.
      if (currentPartCount != partsListSize) {
        if (currentPartKeyInfo.getDataSize() <
            ozoneManager.getMinMultipartUploadPartSize()) {
          LOG.error("MultipartUpload: {} Part number: {} size {}  is less" +
                  " than minimum part size {}", ozoneKey,
              partKeyInfo.getPartNumber(), currentPartKeyInfo.getDataSize(),
              ozoneManager.getMinMultipartUploadPartSize());
          throw new OMException(
              failureMessage(requestedVolume, requestedBucket, keyName) +
                  ". Entity too small.",
              OMException.ResultCodes.ENTITY_TOO_SMALL);
        }
      }

      // As all part keys will have only one version.
      OmKeyLocationInfoGroup currentKeyInfoGroup = currentPartKeyInfo
          .getKeyLocationVersions().get(0);

      // Set partNumber in each block.
      currentKeyInfoGroup.getLocationList().forEach(
          omKeyLocationInfo -> omKeyLocationInfo.setPartNumber(partNumber));

      partLocationInfos.addAll(currentKeyInfoGroup.getLocationList());
      dataSize += currentPartKeyInfo.getDataSize();
    }
    return dataSize;
  }

  private static String failureMessage(String volume, String bucket,
      String keyName) {
    return "Complete Multipart Upload Failed: volume: " +
        volume + " bucket: " + bucket + " key: " + keyName;
  }

  private void updateCache(OMMetadataManager omMetadataManager,
      String dbOzoneKey, String dbMultipartOpenKey, String dbMultipartKey,
      OmKeyInfo omKeyInfo, long transactionLogIndex) {
    // Update cache.
    // 1. Add key entry to key table.
    // 2. Delete multipartKey entry from openKeyTable and multipartInfo table.
    addKeyTableCacheEntry(omMetadataManager, dbOzoneKey, omKeyInfo,
        transactionLogIndex);

    omMetadataManager.getOpenKeyTable(getBucketLayout()).addCacheEntry(
        new CacheKey<>(dbMultipartOpenKey),
        new CacheValue<>(Optional.absent(), transactionLogIndex));
    omMetadataManager.getMultipartInfoTable().addCacheEntry(
        new CacheKey<>(dbMultipartKey),
        new CacheValue<>(Optional.absent(), transactionLogIndex));
  }

}

