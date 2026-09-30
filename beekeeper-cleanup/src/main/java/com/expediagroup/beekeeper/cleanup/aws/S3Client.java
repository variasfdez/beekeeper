/**
 * Copyright (C) 2019-2022 Expedia, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.expediagroup.beekeeper.cleanup.aws;

import static java.lang.String.format;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.DeletedObject;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

public class S3Client {

  private static final int REQUEST_CHUNK_SIZE = 1000;
  private static final int NOT_FOUND_STATUS_CODE = 404;
  private static final Logger log = LoggerFactory.getLogger(S3Client.class);
  // fully qualified: the AWS SDK v2 client shares its simple name with this class
  private final software.amazon.awssdk.services.s3.S3Client amazonS3;
  private final boolean dryRunEnabled;

  public S3Client(software.amazon.awssdk.services.s3.S3Client amazonS3, boolean dryRunEnabled) {
    this.amazonS3 = amazonS3;
    this.dryRunEnabled = dryRunEnabled;
  }

  void deleteObject(String bucket, String key) {
    if (dryRunEnabled) {
      log.info("Dry run - deleting: \"{}/{}\"", bucket, key);
    } else {
      log.info("Deleting \"{}/{}\"", bucket, key);
      amazonS3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
  }

  List<S3Object> listObjects(String bucket, String key) {
    List<S3Object> objectSummaries = new ArrayList<>();
    ListObjectsV2Response listObjectsV2Result;
    String continuationToken = null;
    do {
      ListObjectsV2Request request =
          ListObjectsV2Request.builder()
              .bucket(bucket)
              .prefix(key)
              .encodingType("url")
              .continuationToken(continuationToken)
              .build();
      listObjectsV2Result = amazonS3.listObjectsV2(request);
      objectSummaries.addAll(listObjectsV2Result.contents());
      continuationToken = listObjectsV2Result.nextContinuationToken();
    } while (Boolean.TRUE.equals(listObjectsV2Result.isTruncated()));
    return objectSummaries;
  }

  List<String> deleteObjects(String bucket, List<String> keys) {
    if (keys.isEmpty()) {
      return Collections.emptyList();
    }
    if (!dryRunEnabled) {
      log.info("Attempting to delete a total of {} objects, from [{}] to [{}]", keys.size(), keys.get(0),
          keys.get(keys.size() - 1));
      List<DeletedObject> deletedObjects = new ArrayList<>();
      int totalKeys = keys.size();
      int indexStart;
      int indexEnd = 0;
      while (indexEnd < totalKeys) {
        indexStart = indexEnd;
        indexEnd = nextIndexEnd(indexStart, REQUEST_CHUNK_SIZE, totalKeys);
        DeleteObjectsRequest deleteObjectsRequest =
            DeleteObjectsRequest.builder()
                .bucket(bucket)
                .delete(
                    Delete.builder().objects(objectIdentifiers(keys, indexStart, indexEnd)).build())
                .build();
        DeleteObjectsResponse deleteObjectsResponse = amazonS3.deleteObjects(deleteObjectsRequest);
        throwIfAnyDeletionFailed(bucket, deleteObjectsResponse);
        deletedObjects.addAll(deleteObjectsResponse.deleted());
      }
      log.info("Successfully deleted {} objects", keys.size());
      return deletedObjects.stream().map(DeletedObject::key).collect(Collectors.toList());
    } else {
      return keys.stream()
          .peek(key -> log.info("Dry run - deleting: \"{}/{}\"", bucket, key))
          .collect(Collectors.toList());
    }
  }

  boolean doesObjectExist(String bucket, String key) {
    try {
      getObjectMetadata(bucket, key);
      return true;
    } catch (S3Exception e) {
      if (e.statusCode() == NOT_FOUND_STATUS_CODE) {
        return false;
      }
      throw e;
    }
  }

  HeadObjectResponse getObjectMetadata(String bucket, String key) {
    return amazonS3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
  }

  boolean isEmpty(String bucket, String key, String leafKey) {
    List<S3Object> objectsLeftAtPath =
        amazonS3
            .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).prefix(key + "/").build())
            .contents();
    if (!dryRunEnabled) {
      return objectsLeftAtPath.size() == 0;
    } else {
      String leafKeySentinel = leafKey + S3SentinelFilesCleaner.SENTINEL_SUFFIX;

      for (S3Object s3ObjectSummary : objectsLeftAtPath) {
        String currentKey = s3ObjectSummary.key();
        if (!currentKey.startsWith(leafKey + "/") && !currentKey.equals(leafKeySentinel)) {
          return false;
        }
      }
      return true;
    }
  }

  // v1 threw MultiObjectDeleteException when any key failed; v2 returns the failures instead
  private void throwIfAnyDeletionFailed(String bucket, DeleteObjectsResponse response) {
    if (response.errors().isEmpty()) {
      return;
    }
    String failedDeletions =
        response.errors().stream()
            .map(error -> format("'%s' (%s: %s)", error.key(), error.code(), error.message()))
            .collect(Collectors.joining(", "));
    throw S3Exception.builder()
        .message(format("Failed to delete objects from bucket \"%s\": %s", bucket, failedDeletions))
        .build();
  }

  private List<ObjectIdentifier> objectIdentifiers(
      List<String> keys, int indexStart, int indexEnd) {
    return keys.subList(indexStart, indexEnd).stream()
        .map(key -> ObjectIdentifier.builder().key(key).build())
        .collect(Collectors.toList());
  }

  private int nextIndexEnd(final int indexStart, final int chunkSize, final int totalKeys) {
    int calculatedNextIndexEnd = indexStart + chunkSize;
    return Math.min(calculatedNextIndexEnd, totalKeys);
  }
}
